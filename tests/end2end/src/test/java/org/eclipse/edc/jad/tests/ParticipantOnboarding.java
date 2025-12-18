/*
 *  Copyright (c) 2025 Metaform Systems, Inc.
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Metaform Systems, Inc. - initial API and implementation
 *
 */

package org.eclipse.edc.jad.tests;

import org.eclipse.edc.jad.tests.model.ClientCredentials;
import org.eclipse.edc.jad.tests.model.ParticipantProfile;
import org.eclipse.edc.spi.monitor.Monitor;

import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static io.restassured.RestAssured.given;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.eclipse.edc.jad.tests.Constants.BASE_URL;
import static org.eclipse.edc.jad.tests.KeycloakApi.createKeycloakToken;

/**
 * Takes care of onboarding a single participant into the test Dataspace
 *
 * @param participantName       A human-readable name for the participant. Will be used to create a tenant in CFM
 * @param participantContextDid The Web:DID of the participant.
 * @param vaultToken            A token for Hashicorp Vault which grants access to the {@code v1/secret} secret engine.
 * @param monitor               A monitor for some logging
 */
public record ParticipantOnboarding(String participantName, String participantContextDid,
                                    String vaultToken, Monitor monitor) {

    @SuppressWarnings("unchecked")
    public ClientCredentials execute(String cellId) {

        monitor.info("Creating tenant for %s".formatted(participantName));
        var tenantId = createTenant(participantName);
        monitor.info("Deploy dataspace profile");
        monitor.info("Deploy participant profile");
        var profileId = deployParticipantProfile(tenantId, cellId, participantContextDid);

        monitor.info("Waiting for dataspace profile to become active");
        await().atMost(20, SECONDS)
                .until(() -> {
                    var participantProfile = getParticipantProfile(tenantId, profileId);
                    return participantProfile.getVpas().stream().allMatch(vpa -> vpa.getState().equalsIgnoreCase("active"));
                });

        monitor.info("Participant Profile is active. Verifying state properties");

        var profile = getParticipantProfile(tenantId, profileId);
        var state = (Map<String, Object>) profile.getProperties().get("cfm.vpa.state");

        assertThat(state)
                .hasFieldOrProperty("holderPid")
                .hasFieldOrProperty("participantContextId")
                .hasFieldOrProperty("credentialRequest");

        var participantContextId = state.get("participantContextId").toString();
        var secret = getVaultSecret(participantContextId);

        var token = createKeycloakToken(participantContextId, secret, "identity-api:write", "identity-api:read");

        monitor.info("Waiting for credential issuance");

        var holderPid = state.get("holderPid");
        assertThat(holderPid).withFailMessage(() -> "holderPid should be on the Orchestration's output data").isNotNull();
        waitForCredentialIssuance(participantContextId, token, holderPid.toString());


        return new ClientCredentials(participantContextId, secret);
    }

    //we could the full HashicorpVault for this, but a REST request is simpler here
    private String getVaultSecret(String participantContextId) {
        return given()
                .baseUri(Constants.VAULT_URL)
                .header("X-Vault-Token", vaultToken)
                .get("/v1/secret/data/%s".formatted(participantContextId))
                .then()
                .log().ifValidationFails()
                .statusCode(200)
                .extract().body().jsonPath().getString("data.data.content");
    }

    /**
     * Retrieves an Orchestration object by its ID.
     *
     * @param profileId the unique identifier of the orchestration to retrieve
     * @return the Orchestration object
     */
    private ParticipantProfile getParticipantProfile(String tenant, String profileId) {
        return given()
                .baseUri(Constants.TM_BASE_URL)
                .contentType(Constants.APPLICATION_JSON)
                .get("/api/v1alpha1/tenants/%s/participant-profiles/%s".formatted(tenant, profileId))
                .then()
                .log().ifValidationFails()
                .statusCode(200)
                .extract().body().as(ParticipantProfile.class);
    }

    /**
     * Queries an orchestration object by its correlation ID, which is the participantProfileID
     *
     * @param participantProfileId the participant profile ID
     * @return the orchestration ID
     */
    private String queryOrchestrationByProfileId(String participantProfileId) {
        var orchestrationId = new AtomicReference<String>();
        await().atMost(20, SECONDS)
                .pollInterval(1, SECONDS).untilAsserted(() -> {
                    var body = given()
                            .baseUri(Constants.PM_BASE_URL)
                            .contentType(Constants.APPLICATION_JSON)
                            .body("""
                                    {
                                         "predicate": "correlationId = '%s'"
                                    }
                                    """.formatted(participantProfileId))
                            .post("/api/v1alpha1/orchestrations/query")
                            .then()
                            .log().ifValidationFails()
                            .statusCode(200)
                            .extract().body();
                    orchestrationId.set(body.jsonPath().getString("[0].id"));

                });
        return orchestrationId.get();
    }


    /**
     * Deploys (and creates) a participant profile in CFM.
     *
     * @param tenantId              the tenant ID.
     * @param cellId                the cell ID.
     * @param participantContextDid the Web:DID of the participant.
     * @return the participant profile ID.
     */
    private String deployParticipantProfile(String tenantId, String cellId, String participantContextDid) {
        return given()
                .baseUri(Constants.TM_BASE_URL)
                .contentType(Constants.APPLICATION_JSON)
                .body("""
                        {
                            "identifier": "%s",
                            "properties": {},
                            "cellId": "%s"
                        }
                        """.formatted(participantContextDid, cellId))
                .post("/api/v1alpha1/tenants/%s/participant-profiles".formatted(tenantId))
                .then()
                .log().ifValidationFails()
                .statusCode(202)
                .extract().body().jsonPath().getString("id");
    }


    /**
     * Creates a tenant in CFM.
     *
     * @param tenantName thhe name of the tenant. only used for display purposes
     * @return the tenant ID.
     */
    private String createTenant(String tenantName) {
        return given()
                .baseUri(Constants.TM_BASE_URL)
                .contentType(Constants.APPLICATION_JSON)
                .body("""
                        {
                            "properties": {
                                "name": "%s",
                                "location": "eu"
                            }
                        }
                        """.formatted(tenantName))
                .post("/api/v1alpha1/tenants")
                .then()
                .log().ifValidationFails()
                .statusCode(201)
                .extract().body().jsonPath().getString("id");
    }


    private void waitForCredentialIssuance(String participantContextId, String userToken, String holderPid) {
        await().atMost(20, SECONDS)
                .pollInterval(1, SECONDS).until(() -> {
                    var pcB64 = Base64.getUrlEncoder().encodeToString(participantContextId.getBytes());
                    var response = given()
                            .baseUri(BASE_URL)
                            .contentType("application/json")
                            .auth().oauth2(userToken)
                            .get("/cs/api/identity/v1alpha/participants/%s/credentials/request/%s".formatted(pcB64, holderPid))
                            .then()
                            .log().ifValidationFails()
                            .statusCode(200)
                            .extract()
                            .body().jsonPath().getString("status");
                    return "ISSUED".equals(response);
                });
    }
}
