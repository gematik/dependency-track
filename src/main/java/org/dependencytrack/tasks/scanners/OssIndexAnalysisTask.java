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
package org.dependencytrack.tasks.scanners;

import alpine.Config;
import alpine.common.logging.Logger;
import alpine.common.metrics.Metrics;
import alpine.common.util.Pageable;
import alpine.event.framework.Event;
import alpine.event.framework.Subscriber;
import alpine.model.ConfigProperty;
import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURL;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.dependencytrack.common.ConfigKey;
import org.dependencytrack.common.HttpClientPool;
import org.dependencytrack.common.ManagedHttpClientFactory;
import org.dependencytrack.event.OssIndexAnalysisEvent;
import org.dependencytrack.model.Component;
import org.dependencytrack.model.ConfigPropertyConstants;
import org.dependencytrack.model.Cwe;
import org.dependencytrack.model.Vulnerability;
import org.dependencytrack.model.VulnerabilityAlias;
import org.dependencytrack.model.VulnerabilityAnalysisLevel;
import org.dependencytrack.parser.common.resolver.CweResolver;
import org.dependencytrack.parser.ossindex.OssIndexParser;
import org.dependencytrack.parser.ossindex.model.ComponentReport;
import org.dependencytrack.parser.ossindex.model.ComponentReportVulnerability;
import org.dependencytrack.persistence.QueryManager;
import org.dependencytrack.util.CvssUtil;
import org.dependencytrack.util.DebugDataEncryption;
import org.dependencytrack.util.HttpUtil;
import org.dependencytrack.util.NotificationUtil;
import org.dependencytrack.util.VulnerabilityUtil;
import org.json.JSONObject;
import org.metaeffekt.core.security.cvss.v2.Cvss2;
import org.metaeffekt.core.security.cvss.v3.Cvss3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.dependencytrack.common.ConfigKey.OSSINDEX_RETRY_BACKOFF_INITIAL_DURATION_MS;
import static org.dependencytrack.common.ConfigKey.OSSINDEX_RETRY_BACKOFF_MAX_DURATION_MS;
import static org.dependencytrack.common.ConfigKey.OSSINDEX_RETRY_BACKOFF_MULTIPLIER;
import static org.dependencytrack.common.ConfigKey.OSSINDEX_RETRY_MAX_ATTEMPTS;
import static org.dependencytrack.util.RetryUtil.logRetryEventWith;
import static org.dependencytrack.util.RetryUtil.maybeClosePreviousResult;
import static org.dependencytrack.util.RetryUtil.withExponentialBackoff;
import static org.dependencytrack.util.RetryUtil.withTransientCause;
import static org.dependencytrack.util.RetryUtil.withTransientErrorCode;

/**
 * Subscriber task that performs an analysis of component using Sonatype OSS Index REST API.
 *
 * @author Steve Springett
 * @since 3.2.0
 */
public class OssIndexAnalysisTask extends BaseComponentAnalyzerTask implements CacheableScanTask, Subscriber {

    private static final String DEFAULT_API_BASE_URL = "https://ossindex.sonatype.org";
    private static final Logger LOGGER = Logger.getLogger(OssIndexAnalysisTask.class);
    private static final Retry RETRY;

    private final String apiBaseUrl;
    private String apiUsername;
    private String apiToken;
    private boolean aliasSyncEnabled;
    private VulnerabilityAnalysisLevel vulnerabilityAnalysisLevel;

    static {
        final RetryRegistry registry = RetryRegistry.of(RetryConfig.<CloseableHttpResponse>custom()
                .intervalFunction(withExponentialBackoff(
                        OSSINDEX_RETRY_BACKOFF_INITIAL_DURATION_MS,
                        OSSINDEX_RETRY_BACKOFF_MULTIPLIER,
                        OSSINDEX_RETRY_BACKOFF_MAX_DURATION_MS
                ))
                .maxAttempts(Config.getInstance().getPropertyAsInt(OSSINDEX_RETRY_MAX_ATTEMPTS))
                .consumeResultBeforeRetryAttempt(maybeClosePreviousResult())
                .retryOnException(withTransientCause())
                .retryOnResult(withTransientErrorCode())
                .failAfterMaxAttempts(true)
                .build());
        RETRY = registry.retry("ossindex-api");
        RETRY.getEventPublisher()
                .onIgnoredError(logRetryEventWith(LOGGER))
                .onError(logRetryEventWith(LOGGER))
                .onRetry(logRetryEventWith(LOGGER));
        TaggedRetryMetrics
                .ofRetryRegistry(registry)
                .bindTo(Metrics.getRegistry());
    }

    public OssIndexAnalysisTask() {
        this(DEFAULT_API_BASE_URL);
    }

    OssIndexAnalysisTask(final String apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl;
    }

    public AnalyzerIdentity getAnalyzerIdentity() {
        return AnalyzerIdentity.OSSINDEX_ANALYZER;
    }

    /**
     * {@inheritDoc}
     */
    public void inform(final Event e) {
        if (!(e instanceof final OssIndexAnalysisEvent event)) {
            return;
        }
        if (!super.isEnabled(ConfigPropertyConstants.SCANNER_OSSINDEX_ENABLED)) {
            return;
        }

        try (final var qm = new QueryManager()) {
            final ConfigProperty apiUsernameProperty = qm.getConfigProperty(
                    ConfigPropertyConstants.SCANNER_OSSINDEX_API_USERNAME.getGroupName(),
                    ConfigPropertyConstants.SCANNER_OSSINDEX_API_USERNAME.getPropertyName()
            );
            final ConfigProperty apiTokenProperty = qm.getConfigProperty(
                    ConfigPropertyConstants.SCANNER_OSSINDEX_API_TOKEN.getGroupName(),
                    ConfigPropertyConstants.SCANNER_OSSINDEX_API_TOKEN.getPropertyName()
            );
            if (apiUsernameProperty == null || apiUsernameProperty.getPropertyValue() == null
                    || apiTokenProperty == null || apiTokenProperty.getPropertyValue() == null) {
                LOGGER.warn("An API username or token has not been specified for use with OSS Index. Using anonymous access");
            } else {
                try {
                    apiUsername = apiUsernameProperty.getPropertyValue();
                    apiToken = DebugDataEncryption.decryptAsString(apiTokenProperty.getPropertyValue());
                } catch (Exception ex) {
                    // NB: OSS Index can be used without AuthN, however stricter rate limiting may apply.
                    // We favour "service degradation" over "service outage" here. Analysis will continue
                    // to work, although more retries may need to be performed until a new token is supplied.
                    LOGGER.error("An error occurred decrypting the OSS Index API Token; Continuing without authentication", ex);
                }
            }
            aliasSyncEnabled = super.isEnabled(ConfigPropertyConstants.SCANNER_OSSINDEX_ALIAS_SYNC_ENABLED);
        }

        LOGGER.info("Starting Sonatype OSS Index analysis task");
        vulnerabilityAnalysisLevel = event.analysisLevel();
        if (!event.components().isEmpty()) {
            analyze(event.components());
        }
        LOGGER.info("Sonatype OSS Index analysis complete");
    }

    /**
     * Determines if the {@link OssIndexAnalysisTask} is capable of analyzing the specified Component.
     *
     * @param component the Component to analyze
     * @return true if OssIndexAnalysisTask should analyze, false if not
     */
    public boolean isCapable(final Component component) {
        return component.getPurl() != null
                && component.getPurl().getName() != null
                && component.getPurl().getVersion() != null;
    }

    /**
     * Determines if the {@link OssIndexAnalysisTask} should analyze the specified PackageURL.
     *
     * @param purl the PackageURL to analyze
     * @return true if OssIndexAnalysisTask should analyze, false if not
     */
    public boolean shouldAnalyze(final PackageURL purl) {
        return !isCacheCurrent(Vulnerability.Source.OSSINDEX, apiBaseUrl, purl.toString());
    }

    /**
     * Analyzes the specified component from local {@link org.dependencytrack.model.ComponentAnalysisCache}.
     *
     * @param component component the Component to analyze from cache
     */
    public void applyAnalysisFromCache(final Component component) {
        applyAnalysisFromCache(Vulnerability.Source.OSSINDEX, apiBaseUrl, component.getPurl().toString(), component, getAnalyzerIdentity(), vulnerabilityAnalysisLevel);
    }

    /**
     * Analyzes a list of Components.
     *
     * @param components a list of Components
     */
    public void analyze(final List<Component> components) {
        Map<Boolean, List<Component>> componentsPartitionByCacheValidity = components.stream()
                .filter(component -> !component.isInternal() && isCapable(component))
                .collect(Collectors.partitioningBy(component -> isCacheCurrent(Vulnerability.Source.OSSINDEX, apiBaseUrl, component.getPurl().toString())));
        List<Component> componentWithValidAnalysisFromCache = componentsPartitionByCacheValidity.get(true);
        componentWithValidAnalysisFromCache.forEach(component -> applyAnalysisFromCache(Vulnerability.Source.OSSINDEX, apiBaseUrl, component.getPurl().toString(), component, getAnalyzerIdentity(), vulnerabilityAnalysisLevel));
        List<Component> componentWithInvalidAnalysisFromCache = componentsPartitionByCacheValidity.get(false);
        final Pageable<Component> paginatedComponents = new Pageable<>(Config.getInstance().getPropertyAsInt(ConfigKey.OSSINDEX_REQUEST_MAX_PURL), componentWithInvalidAnalysisFromCache);
        while (!paginatedComponents.isPaginationComplete()) {
            final List<String> coordinates = new ArrayList<>();
            final List<Component> paginatedList = paginatedComponents.getPaginatedList();
            paginatedList.forEach(component -> coordinates.add(minimizePurl(component.getPurl())));
            if (!CollectionUtils.isEmpty(coordinates)) {
                final JSONObject json = new JSONObject();
                json.put("coordinates", coordinates);
                try {
                    final List<ComponentReport> report = submit(json);
                    processResults(report, paginatedList);
                } catch (Throwable ex) {
                    handleRequestException(LOGGER, ex);
                    return;
                }

                LOGGER.info("Analyzing " + coordinates.size() + " component(s)");
            }
            paginatedComponents.nextPage();
        }
    }

    /**
     * Sonatype OSSIndex (as of December 2018) has an issue that fails to identify vulnerabilities when
     * HTTP POST is used and PackageURL is specified that contains qualifiers (and possibly a subpath).
     * Therefore, this method will return a String representation of a PackageURL without qualifier
     * or subpath.
     * <p>
     * Additionally, as of October 2021, versions prefixed with "v" (as commonly done in the Go and PHP ecosystems)
     * are triggering a bug in OSS Index that causes all vulnerabilities for the given component to be returned,
     * not just the ones for the requested version: https://github.com/OSSIndex/vulns/issues/129#issuecomment-740666614
     * As a result, this method will remove "v" prefixes from versions.
     * <p>
     * This method should be removed at a future date when OSSIndex resolves the issues.
     * <p>
     * TODO: Delete this method and workaround for OSSIndex bugs once Sonatype resolves them.
     *
     * @since 3.4.0
     */
    @Deprecated
    private static String minimizePurl(final PackageURL purl) {
        if (purl == null) {
            return null;
        }
        String p = purl.canonicalize();
        p = p.replaceFirst("@v", "@");
        if (p.contains("?")) {
            p = p.substring(0, p.lastIndexOf("?"));
        }
        if (p.contains("#")) {
            p = p.substring(0, p.lastIndexOf("#"));
        }
        return p;
    }

    /**
     * Submits the payload to the Sonatype OSS Index service
     */
    private List<ComponentReport> submit(final JSONObject payload) throws Throwable {
        HttpPost request = new HttpPost("%s/api/v3/component-report".formatted(apiBaseUrl));
        request.addHeader(HttpHeaders.ACCEPT, "application/json");
        request.addHeader(HttpHeaders.CONTENT_TYPE, "application/json");
        request.addHeader(HttpHeaders.USER_AGENT, ManagedHttpClientFactory.getUserAgent());
        request.setEntity(new StringEntity(payload.toString()));
        if (apiUsername != null && apiToken != null) {
            request.addHeader("Authorization", HttpUtil.basicAuthHeaderValue(apiUsername, apiToken));
        }
        try (final CloseableHttpResponse response = RETRY.executeCheckedSupplier(() -> HttpClientPool.getClient().execute(request))) {
            HttpEntity responseEntity = response.getEntity();
            String responseString = EntityUtils.toString(responseEntity);
            if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                final OssIndexParser parser = new OssIndexParser();
                return parser.parse(responseString);
            } else {
                handleUnexpectedHttpResponse(LOGGER, apiBaseUrl, response.getStatusLine().getStatusCode(), response.getStatusLine().getReasonPhrase());
            }
        }
        return new ArrayList<>();

    }

    private void processResults(final List<ComponentReport> report, final List<Component> componentsScanned) {
        try (QueryManager qm = new QueryManager()) {
            for (final ComponentReport componentReport : report) {
                for (final Component c : componentsScanned) {
                    //final String componentPurl = component.getPurl().canonicalize(); // todo: put this back when minimizePurl() is removed
                    final String componentPurl = minimizePurl(c.getPurl());
                    final PackageURL sonatypePurl = oldPurlResolver(componentReport.getCoordinates());
                    final String minimalSonatypePurl = minimizePurl(sonatypePurl);
                    if (componentPurl != null && (componentPurl.equals(componentReport.getCoordinates()) ||
                            (sonatypePurl != null && componentPurl.equals(minimalSonatypePurl)))) {
                        /*
                        Found the component
                         */
                        final Component component = qm.getObjectByUuid(Component.class, c.getUuid()); // Refresh component and attach to current pm.
                        if (component == null) continue;
                        for (final ComponentReportVulnerability reportedVuln : componentReport.getVulnerabilities()) {
                            if (reportedVuln.getCve() != null) {
                                Vulnerability vulnerability = qm.getVulnerabilityByVulnId(
                                        Vulnerability.Source.NVD, reportedVuln.getCve());
                                if (vulnerability != null) {
                                    NotificationUtil.analyzeNotificationCriteria(qm, vulnerability, component, vulnerabilityAnalysisLevel);
                                    qm.addVulnerability(vulnerability, component, this.getAnalyzerIdentity(), reportedVuln.getId(), reportedVuln.getReference());
                                    addVulnerabilityToCache(component, vulnerability);
                                } else {
                                    /*
                                    The vulnerability reported by OSS Index is not in Dependency-Track yet. This could be
                                    due to timing issue or the vuln reported may be in a reserved state and not available
                                    through traditional feeds. Regardless, the vuln needs to be added to the database.
                                     */
                                    vulnerability = qm.createVulnerability(generateVulnerability(qm, reportedVuln), false);
                                    NotificationUtil.analyzeNotificationCriteria(qm, vulnerability, component, vulnerabilityAnalysisLevel);
                                    qm.addVulnerability(vulnerability, component, this.getAnalyzerIdentity(), reportedVuln.getId(), reportedVuln.getReference());
                                    addVulnerabilityToCache(component, vulnerability);
                                }
                            } else {
                                /*
                                The vulnerability is not from the NVD. Set the source to OSSINDEX
                                 */
                                Vulnerability vulnerability = qm.getVulnerabilityByVulnId(Vulnerability.Source.OSSINDEX, reportedVuln.getId());
                                if (vulnerability == null) {
                                    vulnerability = qm.createVulnerability(generateVulnerability(qm, reportedVuln), false);
                                }
                                // In some cases, OSS Index may publish a vulnerability before the NVD does. In this case,
                                // a sonatype id will be assigned to the vulnerability. However, it is possible that at
                                // a later time, the vulnerability will be published to the NVD. Therefore, add an alias.
                                // The "startsWith CVE" is unfortunately necessary as of 11 June 2022, OSS Index has
                                // multiple vulnerabilities with sonatype identifiers in the cve field.
                                if (aliasSyncEnabled && reportedVuln.getCve() != null && reportedVuln.getCve().startsWith("CVE-")) {
                                    LOGGER.debug("Updating vulnerability alias for " + reportedVuln.getId());
                                    final VulnerabilityAlias alias = new VulnerabilityAlias();
                                    alias.setSonatypeId(reportedVuln.getId());
                                    alias.setCveId(reportedVuln.getCve());
                                    qm.synchronizeVulnerabilityAlias(alias);
                                }
                                NotificationUtil.analyzeNotificationCriteria(qm, vulnerability, component, vulnerabilityAnalysisLevel);
                                qm.addVulnerability(vulnerability, component, this.getAnalyzerIdentity(), reportedVuln.getId(), reportedVuln.getReference());
                                addVulnerabilityToCache(component, vulnerability);
                            }
                        }
                        updateAnalysisCacheStats(qm, Vulnerability.Source.OSSINDEX, apiBaseUrl, component.getPurl().toString(), component.getCacheResult());
                    }
                }
            }
        }
    }

    /**
     * Generates a Dependency-Track vulnerability object from a Sonatype OSS ComponentReportVulnerability object.
     */
    private Vulnerability generateVulnerability(final QueryManager qm, final ComponentReportVulnerability reportedVuln) {
        final Vulnerability vulnerability = new Vulnerability();
        if (reportedVuln.getCve() != null) {
            vulnerability.setSource(Vulnerability.Source.NVD);
            vulnerability.setVulnId(reportedVuln.getCve());
        } else {
            vulnerability.setSource(Vulnerability.Source.OSSINDEX);
            vulnerability.setVulnId(reportedVuln.getId());
            vulnerability.setTitle(reportedVuln.getTitle());
        }
        vulnerability.setDescription(reportedVuln.getDescription());

        if (reportedVuln.getCwe() != null) {
            final Cwe cwe = CweResolver.getInstance().lookup(reportedVuln.getCwe());
            if (cwe != null) {
                vulnerability.addCwe(cwe);
            }
        }

        final StringBuilder sb = new StringBuilder();
        final String reference = reportedVuln.getReference();
        if (reference != null) {
            sb.append("* [").append(reference).append("](").append(reference).append(")\n");
        }
        for (String externalReference : reportedVuln.getExternalReferences()) {
            sb.append("* [").append(externalReference).append("](").append(externalReference).append(")\n");
        }
        final String references = sb.toString();
        if (references.length() > 0) {
            vulnerability.setReferences(references.substring(0, references.lastIndexOf("\n")));
        }

        if (reportedVuln.getCvssVector() != null) {
            final var cvss = CvssUtil.parse(reportedVuln.getCvssVector());
            if (cvss != null) {
                if (cvss instanceof Cvss2) {
                    vulnerability.applyV2Score(cvss);
                } else if (cvss instanceof Cvss3) {
                    vulnerability.applyV3Score(cvss);
                }
            }
        }

        vulnerability.setSeverity(VulnerabilityUtil.getSeverity(
                vulnerability.getCvssV2BaseScore(),
                vulnerability.getCvssV3BaseScore(),
                vulnerability.getOwaspRRLikelihoodScore(),
                vulnerability.getOwaspRRTechnicalImpactScore(),
                vulnerability.getOwaspRRBusinessImpactScore()
        ));

        return vulnerability;
    }

    /**
     * Sonatype OSS Index currently uses an old/outdated version of the PackageURL specification.
     * Attempt to convert it into the current spec format and return it.
     */
    private PackageURL oldPurlResolver(String coordinates) {
        try {
            // Check if OSSIndex has updated their implementation or not
            if (coordinates.startsWith("pkg:")) {
                return new PackageURL(coordinates);
            }
            // Nope, they're still using the 'old' style. Force update it.
            return new PackageURL("pkg:" + coordinates.replaceFirst(":", "/"));
        } catch (MalformedPackageURLException e) {
            return null;
        }
    }
}
