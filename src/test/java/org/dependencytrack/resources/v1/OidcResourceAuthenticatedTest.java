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
 */
package org.dependencytrack.resources.v1;

import alpine.model.MappedOidcGroup;
import alpine.model.OidcGroup;
import alpine.model.Team;
import alpine.server.filters.ApiFilter;
import alpine.server.filters.AuthenticationFilter;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.dependencytrack.JerseyTestExtension;
import org.dependencytrack.ResourceTest;
import org.dependencytrack.resources.v1.vo.MappedOidcGroupRequest;
import org.glassfish.jersey.server.ResourceConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OidcResourceAuthenticatedTest extends ResourceTest {

    @RegisterExtension
    public static JerseyTestExtension jersey = new JerseyTestExtension(
            () -> new ResourceConfig(OidcResource.class)
                    .register(ApiFilter.class)
                    .register(AuthenticationFilter.class));

    @Test
    void retrieveGroupsShouldReturnListOfGroups() {
        final OidcGroup oidcGroup = new OidcGroup();
        oidcGroup.setName("groupName");
        qm.persist(oidcGroup);

        final Response response = jersey.target(V1_OIDC + "/group")
                .request().header(X_API_KEY, apiKey).get();

        assertThat(response.getStatus()).isEqualTo(200);

        final JsonArray jsonGroups = parseJsonArray(response);
        assertThat(jsonGroups).hasSize(1);
        assertThat(jsonGroups.getJsonObject(0).getString("name")).isEqualTo("groupName");
    }

    @Test
    void retrieveGroupsShouldReturnEmptyListWhenNoGroupsWhereFound() {
        final Response response = jersey.target(V1_OIDC + "/group")
                .request().header(X_API_KEY, apiKey).get();

        assertThat(response.getStatus()).isEqualTo(200);

        final JsonArray jsonGroups = parseJsonArray(response);
        assertThat(jsonGroups).isEmpty();
    }

    @Test
    void createGroupShouldReturnCreatedGroup() {
        final OidcGroup oidcGroup = new OidcGroup();
        oidcGroup.setName("groupName");

        final Response response = jersey.target(V1_OIDC + "/group")
                .request()
                .header(X_API_KEY, apiKey)
                .put(Entity.entity(oidcGroup, MediaType.APPLICATION_JSON));

        assertThat(response.getStatus()).isEqualTo(201);

        final JsonObject group = parseJsonObject(response);
        assertThat(group.getJsonObject("id")).isNull();
        assertThat(group.getString("uuid")).isNotEmpty();
        assertThat(group.getString("name")).isEqualTo("groupName");
    }

    @Test
    void createGroupShouldIndicateConflictWhenGroupAlreadyExists() {
        qm.createOidcGroup("groupName");

        final OidcGroup oidcGroup = new OidcGroup();
        oidcGroup.setName("groupName");

        final Response response = jersey.target(V1_OIDC + "/group")
                .request()
                .header(X_API_KEY, apiKey)
                .put(Entity.entity(oidcGroup, MediaType.APPLICATION_JSON));

        assertThat(response.getStatus()).isEqualTo(409);
    }

    @Test
    void createGroupShouldIndicateBadRequestWhenRequestIsInvalid() {
        final OidcGroup oidcGroup = new OidcGroup();
        oidcGroup.setName(" ");

        final Response response = jersey.target(V1_OIDC + "/group")
                .request()
                .header(X_API_KEY, apiKey)
                .put(Entity.entity(oidcGroup, MediaType.APPLICATION_JSON));

        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    void updateGroupShouldUpdateAndReturnGroup() {
        final OidcGroup existingGroup = qm.createOidcGroup("groupName");

        final OidcGroup jsonGroup = new OidcGroup();
        jsonGroup.setUuid(existingGroup.getUuid());
        jsonGroup.setName("newGroupName");

        final Response response = jersey.target(V1_OIDC + "/group").request()
                .header(X_API_KEY, apiKey)
                .post(Entity.entity(jsonGroup, MediaType.APPLICATION_JSON));

        assertThat(response.getStatus()).isEqualTo(200);

        final JsonObject groupObject = parseJsonObject(response);
        assertThat(groupObject.getString("uuid")).isEqualTo(jsonGroup.getUuid().toString());
        assertThat(groupObject.getString("name")).isEqualTo("newGroupName");
    }

    @Test
    void updateGroupShouldIndicateBadRequestWhenRequestBodyIsInvalid() {
        final OidcGroup jsonGroup = new OidcGroup();

        final Response response = jersey.target(V1_OIDC + "/group").request()
                .header(X_API_KEY, apiKey)
                .post(Entity.entity(jsonGroup, MediaType.APPLICATION_JSON));

        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    void updateGroupShouldIndicateNotFoundWhenGroupDoesNotExist() {
        final OidcGroup jsonGroup = new OidcGroup();
        jsonGroup.setUuid(UUID.randomUUID());
        jsonGroup.setName("groupName");

        final Response response = jersey.target(V1_OIDC + "/group").request()
                .header(X_API_KEY, apiKey)
                .post(Entity.entity(jsonGroup, MediaType.APPLICATION_JSON));

        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    void deleteGroupShouldDeleteGroupAndIndicateNoContent() {
        final OidcGroup existingOidcGroup = qm.createOidcGroup("groupName");

        final Response response = jersey.target(V1_OIDC + "/group/" + existingOidcGroup.getUuid())
                .request()
                .header(X_API_KEY, apiKey)
                .delete();

        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(qm.getObjectByUuid(OidcGroup.class, existingOidcGroup.getUuid())).isNull();
    }

    @Test
    void deleteGroupShouldIndicateNotFoundWhenGroupDoesNotExist() {
        final Response response = jersey.target(V1_OIDC + "/group/" + UUID.randomUUID())
                .request()
                .header(X_API_KEY, apiKey)
                .delete();

        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    void retrieveTeamsMappedToGroupShouldReturnTeamsMappedToSpecifiedGroup() {
        final OidcGroup oidcGroup = qm.createOidcGroup("groupName");
        final Team team = qm.createTeam("teamName");
        qm.createMappedOidcGroup(team, oidcGroup);

        final Response response = jersey.target(V1_OIDC + "/group/" + oidcGroup.getUuid() + "/team")
                .request().header(X_API_KEY, apiKey).get();

        assertThat(response.getStatus()).isEqualTo(200);

        final JsonArray teamsArray = parseJsonArray(response);
        assertThat(teamsArray).hasSize(1);
        assertThat(teamsArray.getJsonObject(0).getString("name")).isEqualTo("teamName");
    }

    @Test
    void retrieveTeamsMappedToGroupShouldIndicateNotFoundWhenGroupDoesNotExit() {
        final Response response = jersey.target(V1_OIDC + "/group/" + UUID.randomUUID() + "/team")
                .request().header(X_API_KEY, apiKey).get();

        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    void addMappingShouldIndicateBadRequestWhenRequestIsInvalid() {
        final MappedOidcGroupRequest request = new MappedOidcGroupRequest("not-a-uuid", "not-a-uuid");

        final Response response = jersey.target(V1_OIDC + "/mapping")
                .request()
                .header(X_API_KEY, apiKey)
                .put(Entity.entity(request, MediaType.APPLICATION_JSON));

        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    void addMappingShouldIndicateNotFoundWhenTeamDoesNotExist() {
        final OidcGroup group = qm.createOidcGroup("groupName");

        final MappedOidcGroupRequest request = new MappedOidcGroupRequest(UUID.randomUUID().toString(), group.getUuid().toString());

        final Response response = jersey.target(V1_OIDC + "/mapping")
                .request()
                .header(X_API_KEY, apiKey)
                .put(Entity.entity(request, MediaType.APPLICATION_JSON));

        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    void addMappingShouldIndicateNotFoundWhenGroupDoesNotExist() {
        final Team team = qm.createTeam("teamName");

        final MappedOidcGroupRequest request = new MappedOidcGroupRequest(team.getUuid().toString(), UUID.randomUUID().toString());

        final Response response = jersey.target(V1_OIDC + "/mapping")
                .request()
                .header(X_API_KEY, apiKey)
                .put(Entity.entity(request, MediaType.APPLICATION_JSON));

        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    void addMappingShouldIndicateConflictWhenMappingAlreadyExists() {
        final Team team = qm.createTeam("teamName");
        final OidcGroup group = qm.createOidcGroup("groupName");
        qm.createMappedOidcGroup(team, group);

        final MappedOidcGroupRequest request = new MappedOidcGroupRequest(team.getUuid().toString(), group.getUuid().toString());

        final Response response = jersey.target(V1_OIDC + "/mapping")
                .request()
                .header(X_API_KEY, apiKey)
                .put(Entity.entity(request, MediaType.APPLICATION_JSON));

        assertThat(response.getStatus()).isEqualTo(409);
    }

    @Test
    void addMappingShouldReturnCreatedMapping() {
        final Team team = qm.createTeam("teamName");
        final OidcGroup group = qm.createOidcGroup("groupName");

        final MappedOidcGroupRequest request = new MappedOidcGroupRequest(team.getUuid().toString(), group.getUuid().toString());

        final Response response = jersey.target(V1_OIDC + "/mapping")
                .request()
                .header(X_API_KEY, apiKey)
                .put(Entity.entity(request, MediaType.APPLICATION_JSON));

        assertThat(response.getStatus()).isEqualTo(200);

        final JsonObject mapping = parseJsonObject(response);
        assertThat(mapping.getJsonObject("id")).isNull();
        assertThat(mapping.getString("uuid")).isNotEmpty();
        assertThat(mapping.getJsonObject("team")).isNull();
        assertThat(mapping.getJsonObject("group")).isNotNull();
    }

    @Test
    void deleteMappingByUuidShouldDeleteMappingAndIndicateNoContent() {
        final Team team = qm.createTeam("teamName");
        final OidcGroup group = qm.createOidcGroup("groupName");
        final MappedOidcGroup mapping = qm.createMappedOidcGroup(team, group);

        final Response response = jersey.target(V1_OIDC + "/mapping/" + mapping.getUuid())
                .request()
                .header(X_API_KEY, apiKey)
                .delete();

        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(qm.getObjectByUuid(MappedOidcGroup.class, mapping.getUuid())).isNull();
    }

    @Test
    void deleteMappingByUuidShouldIndicateNotFoundWhenMappingDoesNotExist() {
        final Response response = jersey.target(V1_OIDC + "/mapping/" + UUID.randomUUID())
                .request()
                .header(X_API_KEY, apiKey)
                .delete();

        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    void deleteMappingShouldDeleteMappingAndIndicateNoContent() {
        final OidcGroup oidcGroup = qm.createOidcGroup("groupName");
        final Team team = qm.createTeam("teamName");
        final MappedOidcGroup mapping = qm.createMappedOidcGroup(team, oidcGroup);

        final Response response = jersey.target(V1_OIDC + "/group/" + oidcGroup.getUuid() + "/team/" + team.getUuid() + "/mapping").request()
                .header(X_API_KEY, apiKey)
                .delete();

        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(qm.getObjectByUuid(MappedOidcGroup.class, mapping.getUuid())).isNull();
    }

    @Test
    void deleteMappingShouldIndicateNotFoundWhenTeamDoesNotExist() {
        final OidcGroup oidcGroup = qm.createOidcGroup("groupName");

        final Response response = jersey.target(V1_OIDC + "/group/" + oidcGroup.getUuid() + "/team/" + UUID.randomUUID() + "/mapping").request()
                .header(X_API_KEY, apiKey)
                .delete();

        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    void deleteMappingShouldIndicateNotFoundWhenGroupDoesNotExist() {
        final Team team = qm.createTeam("teamName");

        final Response response = jersey.target(V1_OIDC + "/group/" + UUID.randomUUID() + "/team/" + team.getUuid() + "/mapping").request()
                .header(X_API_KEY, apiKey)
                .delete();

        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    void deleteMappingShouldIndicateNotFoundWhenMappingDoesNotExist() {
        final OidcGroup oidcGroup = qm.createOidcGroup("groupName");
        final Team team = qm.createTeam("teamName");

        final Response response = jersey.target(V1_OIDC + "/group/" + oidcGroup.getUuid() + "/team/" + team.getUuid() + "/mapping").request()
                .header(X_API_KEY, apiKey)
                .delete();

        assertThat(response.getStatus()).isEqualTo(404);
    }

}
