/*
 * This file is part of Dependency-Track.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * Copyright (c) OWASP Foundation. All Rights Reserved.
 */
package org.dependencytrack.parser.nvd;

import alpine.common.logging.Logger;
import alpine.event.framework.Event;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.StringUtils;
import org.dependencytrack.event.IndexEvent;
import org.dependencytrack.model.Cwe;
import org.dependencytrack.model.Vulnerability;
import org.dependencytrack.model.VulnerableSoftware;
import org.dependencytrack.parser.common.resolver.CweResolver;
import org.dependencytrack.util.CvssUtil;
import org.dependencytrack.util.VulnerabilityUtil;
import org.metaeffekt.core.security.cvss.v2.Cvss2;
import us.springett.parsers.cpe.exceptions.CpeEncodingException;
import us.springett.parsers.cpe.exceptions.CpeParsingException;
import us.springett.parsers.cpe.values.Part;

import java.io.File;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.sql.Date;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import static org.dependencytrack.parser.nvd.api20.ModelConverter.distinctIgnoringDatastoreIdentity;

/**
 * Parser and processor of NVD data feeds.
 *
 * @author Steve Springett
 * @since 3.0.0
 */
public final class NvdParser {

    private static final Logger LOGGER = Logger.getLogger(NvdParser.class);
    private enum Operator {
        AND,
        OR,
        NONE
    }

    // TODO: Use global ObjectMapper instance once
    // https://github.com/DependencyTrack/dependency-track/pull/2520
    // is merged.
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BiConsumer<Vulnerability, List<VulnerableSoftware>> vulnerabilityConsumer;

    public NvdParser(final BiConsumer<Vulnerability, List<VulnerableSoftware>> vulnerabilityConsumer) {
        this.vulnerabilityConsumer = vulnerabilityConsumer;
    }

    public void parse(final File file) {
        if (!file.getName().endsWith(".json")) {
            return;
        }

        LOGGER.info("Parsing " + file.getName());

        try (final InputStream in = Files.newInputStream(file.toPath());
             final JsonParser jsonParser = objectMapper.createParser(in)) {
            jsonParser.nextToken(); // Position cursor at first token

            // Due to JSON feeds being rather large, do not parse them completely,
            // but "stream" through them. Parsing individual CVE items
            // one-by-one allows for garbage collection to kick in sooner,
            // keeping the overall memory footprint low.
            JsonToken currentToken;
            while (jsonParser.nextToken() != JsonToken.END_OBJECT) {
                final String fieldName = jsonParser.currentName();
                currentToken = jsonParser.nextToken();
                if ("CVE_Items".equals(fieldName)) {
                    if (currentToken == JsonToken.START_ARRAY) {
                        while (jsonParser.nextToken() != JsonToken.END_ARRAY) {
                            final ObjectNode cveItem = jsonParser.readValueAsTree();
                            parseCveItem(cveItem);
                        }
                    } else {
                        jsonParser.skipChildren();
                    }
                } else {
                    jsonParser.skipChildren();
                }
            }
        } catch (Exception e) {
            LOGGER.error("An error occurred while parsing NVD JSON data", e);
        }
        Event.dispatch(new IndexEvent(IndexEvent.Action.COMMIT, Vulnerability.class));
    }

    private void parseCveItem(final ObjectNode cveItem) {
        final Vulnerability vulnerability = new Vulnerability();
        vulnerability.setSource(Vulnerability.Source.NVD);

        // CVE ID
        final var cve = (ObjectNode) cveItem.get("cve");
        final var meta0 = (ObjectNode) cve.get("CVE_data_meta");
        vulnerability.setVulnId(meta0.get("ID").asText());

        // CVE Published and Modified dates
        final String publishedDateString = cveItem.get("publishedDate").asText();
        final String lastModifiedDateString = cveItem.get("lastModifiedDate").asText();
        try {
            if (StringUtils.isNotBlank(publishedDateString)) {
                vulnerability.setPublished(Date.from(OffsetDateTime.parse(publishedDateString).toInstant()));
            }
            if (StringUtils.isNotBlank(lastModifiedDateString)) {
                vulnerability.setUpdated(Date.from(OffsetDateTime.parse(lastModifiedDateString).toInstant()));
            }
        } catch (DateTimeParseException | NullPointerException | IllegalArgumentException e) {
            LOGGER.error("Unable to parse dates from NVD data feed", e);
        }

        // CVE Description
        final var descO = (ObjectNode) cve.get("description");
        final var desc1 = (ArrayNode) descO.get("description_data");
        final StringBuilder descriptionBuilder = new StringBuilder();
        for (int j = 0; j < desc1.size(); j++) {
            final var desc2 = (ObjectNode) desc1.get(j);
            if ("en".equals(desc2.get("lang").asText())) {
                descriptionBuilder.append(desc2.get("value").asText());
                if (j < desc1.size() - 1) {
                    descriptionBuilder.append("\n\n");
                }
            }
        }
        vulnerability.setDescription(descriptionBuilder.toString());

        // CVE Impact
        parseCveImpact(cveItem, vulnerability);

        // CWE
        final var prob0 = (ObjectNode) cve.get("problemtype");
        final var prob1 = (ArrayNode) prob0.get("problemtype_data");
        for (int j = 0; j < prob1.size(); j++) {
            final var prob2 = (ObjectNode) prob1.get(j);
            final var prob3 = (ArrayNode) prob2.get("description");
            for (int k = 0; k < prob3.size(); k++) {
                final var prob4 = (ObjectNode) prob3.get(k);
                if ("en".equals(prob4.get("lang").asText())) {
                    final String cweString = prob4.get("value").asText();
                    if (cweString != null && cweString.startsWith("CWE-")) {
                        final Cwe cwe = CweResolver.getInstance().lookup(cweString);
                        if (cwe != null) {
                            vulnerability.addCwe(cwe);
                        } else {
                            LOGGER.warn("CWE " + cweString + " not found in Dependency-Track database. This could signify an issue with the NVD or with Dependency-Track not having advanced knowledge of this specific CWE identifier.");
                        }
                    }
                }
            }
        }

        // References
        final var ref0 = (ObjectNode) cve.get("references");
        final var ref1 = (ArrayNode) ref0.get("reference_data");
        final StringBuilder sb = new StringBuilder();
        for (int l = 0; l < ref1.size(); l++) {
            final var ref2 = (ObjectNode) ref1.get(l);
            final Iterator<String> fieldNameIter = ref2.fieldNames();
            while (fieldNameIter.hasNext()) {
                final String s = fieldNameIter.next();
                if ("url".equals(s)) {
                    // Convert reference to Markdown format
                    final String url = ref2.get("url").asText();
                    sb.append("* [").append(url).append("](").append(url).append(")\n");
                }
            }
        }
        final String references = sb.toString();
        if (!references.isEmpty()) {
            vulnerability.setReferences(references.substring(0, references.lastIndexOf("\n")));
        }

        // CPE
        List<VulnerableSoftware> vsList = new ArrayList<>();
        final var configurations = (ObjectNode) cveItem.get("configurations");
        final var nodes = (ArrayNode) configurations.get("nodes");
        for (int j = 0; j < nodes.size(); j++) {
            final var node = (ObjectNode) nodes.get(j);
            final List<VulnerableSoftware> vulnerableSoftwareInNode = new ArrayList<>();
            final Operator nodeOperator = Operator.valueOf(node.get("operator").asText(Operator.NONE.name()));
            if (node.has("children")) {
                // https://github.com/DependencyTrack/dependency-track/issues/1033
                final var children = (ArrayNode) node.get("children");
                if (!children.isEmpty()) {
                    for (int l = 0; l < children.size(); l++) {
                        final var child = (ObjectNode) children.get(l);
                        vulnerableSoftwareInNode.addAll(parseCpes(child));
                    }
                } else {
                    vulnerableSoftwareInNode.addAll(parseCpes(node));
                }
            } else {
                vulnerableSoftwareInNode.addAll(parseCpes(node));
            }
            vsList.addAll(reconcile(vulnerableSoftwareInNode, nodeOperator));
        }

        final List<VulnerableSoftware> uniqueVsList = vsList.stream()
                .filter(distinctIgnoringDatastoreIdentity())
                .collect(Collectors.toList());
        vulnerabilityConsumer.accept(vulnerability, uniqueVsList);
    }

    /**
     * CVE configurations may consist of applications and operating systems. In the case of
     * configurations that contain both application and operating system parts, we do not
     * want both types of CPEs to be associated to the vulnerability as it will lead to
     * false positives on the operating system. https://nvd.nist.gov/vuln/detail/CVE-2015-0312
     * is a good example of this as it contains application CPEs describing various versions
     * of Adobe Flash player, but also contains CPEs for all versions of Windows, macOS, and
     * Linux. This method will only return a List of VulnerableSoftware objects which are
     * applications when there are also operating system CPE in list supplied to this method.
     * @param vulnerableSoftwareList a list of all VulnerableSoftware object for a given CVE
     * @return a reconciled list of VulnerableSoftware objects
     */
    private List<VulnerableSoftware> reconcile(List<VulnerableSoftware> vulnerableSoftwareList, final Operator nodeOperator) {
        final List<VulnerableSoftware> appPartList = new ArrayList<>();
        final List<VulnerableSoftware> osPartList = new ArrayList<>();
        if (Operator.AND == nodeOperator) {
            for (VulnerableSoftware vulnerableSoftware: vulnerableSoftwareList) {
                if (vulnerableSoftware.getCpe23() != null && Part.OPERATING_SYSTEM.getAbbreviation().equals(vulnerableSoftware.getPart())) {
                    osPartList.add(vulnerableSoftware);
                }
                if (vulnerableSoftware.getCpe23() != null && Part.APPLICATION.getAbbreviation().equals(vulnerableSoftware.getPart())) {
                    appPartList.add(vulnerableSoftware);
                }
            }
            if (!osPartList.isEmpty() && !appPartList.isEmpty()) {
                return appPartList;
            } else {
                return vulnerableSoftwareList;
            }
        }
        return vulnerableSoftwareList;
    }

    private void parseCveImpact(final ObjectNode cveItem, final Vulnerability vuln) {
        final var impact = (ObjectNode) cveItem.get("impact");
        final var baseMetricV2 = (ObjectNode) impact.get("baseMetricV2");
        if (baseMetricV2 != null) {
            final var cvssV2 = (ObjectNode) baseMetricV2.get("cvssV2");
            if (cvssV2 != null) {
                final var cvss = new Cvss2(cvssV2.get("vectorString").asText());
                vuln.setCvssV2Vector(cvss.toString()); // normalize the vector but use the scores from the feed
                vuln.setCvssV2BaseScore(BigDecimal.valueOf(cvssV2.get("baseScore").asDouble()));
            }
            vuln.setCvssV2ExploitabilitySubScore(BigDecimal.valueOf(baseMetricV2.get("exploitabilityScore").asDouble()));
            vuln.setCvssV2ImpactSubScore(BigDecimal.valueOf(baseMetricV2.get("impactScore").asDouble()));
        }

        final var baseMetricV3 = (ObjectNode) impact.get("baseMetricV3");
        if (baseMetricV3 != null) {
            final var cvssV3 = (ObjectNode) baseMetricV3.get("cvssV3");
            if (cvssV3 != null) {
                final var cvss = CvssUtil.parse(cvssV3.get("vectorString").asText());
                vuln.setCvssV3Vector(cvss.toString()); // normalize the vector but use the scores from the feed
                vuln.setCvssV3BaseScore(BigDecimal.valueOf(cvssV3.get("baseScore").asDouble()));
            }
            vuln.setCvssV3ExploitabilitySubScore(BigDecimal.valueOf(baseMetricV3.get("exploitabilityScore").asDouble()));
            vuln.setCvssV3ImpactSubScore(BigDecimal.valueOf(baseMetricV3.get("impactScore").asDouble()));
        }

        vuln.setSeverity(VulnerabilityUtil.getSeverity(
                vuln.getCvssV2BaseScore(),
                vuln.getCvssV3BaseScore(),
                vuln.getOwaspRRLikelihoodScore(),
                vuln.getOwaspRRTechnicalImpactScore(),
                vuln.getOwaspRRBusinessImpactScore()
        ));
    }

    private List<VulnerableSoftware> parseCpes(final ObjectNode node) {
        final List<VulnerableSoftware> vsList = new ArrayList<>();
        if (node.has("cpe_match")) {
            final var cpeMatches = (ArrayNode) node.get("cpe_match");
            for (int k = 0; k < cpeMatches.size(); k++) {
                final var cpeMatch = (ObjectNode) cpeMatches.get(k);
                if (cpeMatch.get("vulnerable").asBoolean(true)) { // only parse the CPEs marked as vulnerable
                    final VulnerableSoftware vs = generateVulnerableSoftware(cpeMatch);
                    if (vs != null) {
                        vsList.add(vs);
                    }
                }
            }
        }
        return vsList;
    }

    private VulnerableSoftware generateVulnerableSoftware(final ObjectNode cpeMatch) {
        final String cpe23Uri = cpeMatch.get("cpe23Uri").asText();
        final String versionEndExcluding = Optional.ofNullable(cpeMatch.get("versionEndExcluding")).map(JsonNode::asText).orElse(null);
        final String versionEndIncluding = Optional.ofNullable(cpeMatch.get("versionEndIncluding")).map(JsonNode::asText).orElse(null);
        final String versionStartExcluding = Optional.ofNullable(cpeMatch.get("versionStartExcluding")).map(JsonNode::asText).orElse(null);
        final String versionStartIncluding = Optional.ofNullable(cpeMatch.get("versionStartIncluding")).map(JsonNode::asText).orElse(null);

        final VulnerableSoftware vs;
        try {
            vs = ModelConverter.convertCpe23UriToVulnerableSoftware(cpe23Uri);
        } catch (CpeParsingException | CpeEncodingException e) {
            LOGGER.warn("An error occurred while parsing: " + cpe23Uri + " - The CPE is invalid and will be discarded.");
            return null;
        }

        vs.setVulnerable(cpeMatch.get("vulnerable").asBoolean(true));
        vs.setVersionEndExcluding(versionEndExcluding);
        vs.setVersionEndIncluding(versionEndIncluding);
        vs.setVersionStartExcluding(versionStartExcluding);
        vs.setVersionStartIncluding(versionStartIncluding);
        return vs;
    }
}
