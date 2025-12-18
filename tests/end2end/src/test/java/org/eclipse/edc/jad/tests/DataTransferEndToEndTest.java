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

import org.eclipse.edc.jad.tests.model.CatalogResponse;
import org.eclipse.edc.jad.tests.model.ClientCredentials;
import org.eclipse.edc.junit.annotations.EndToEndTest;
import org.eclipse.edc.spi.monitor.ConsoleMonitor;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.edc.jad.tests.Constants.APPLICATION_JSON;
import static org.eclipse.edc.jad.tests.Constants.BASE_URL;
import static org.eclipse.edc.jad.tests.Constants.TM_BASE_URL;
import static org.eclipse.edc.jad.tests.KeycloakApi.createKeycloakToken;
import static org.eclipse.edc.jad.tests.KeycloakApi.getAccessToken;
import static org.eclipse.edc.jsonld.spi.JsonLdKeywords.ID;

/**
 * This test class executes a series of REST requests against several components to verify that an end-to-end
 * data transfer works. It assumes that the deployment to a local KinD cluster has already been performed, but no other
 * manipulation of the cluster has been done.
 * <p>
 */
@EndToEndTest
public class DataTransferEndToEndTest {


    private static final String VAULT_TOKEN = "root";

    static String loadResourceFile(String resourceName) {
        try (var is = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourceName)) {
            if (is == null) {
                throw new RuntimeException("Resource not found: " + resourceName);
            }
            return new String(is.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void testDataTransfer() {
        var monitor = new ConsoleMonitor(ConsoleMonitor.Level.DEBUG, true);


        var adminToken = createKeycloakToken("admin", "edc-v-admin-secret", "issuer-admin-api:write", "identity-api:write", "management-api:write", "identity-api:read");
        createCelExpression(adminToken);

        monitor.info("Create cell and dataspace profile");
        var cellId = getCellId();

        // onboard consumer
        monitor.info("Onboarding consumer");
        var po = new ParticipantOnboarding("consumer", "did:web:identityhub.edc-v.svc.cluster.local%3A7083:consumer", VAULT_TOKEN, monitor.withPrefix("Consumer"));
        var consumerCredentials = po.execute(cellId);

        // onboard provider
        monitor.info("Onboarding provider");
        var providerPo = new ParticipantOnboarding("provider", "did:web:identityhub.edc-v.svc.cluster.local%3A7083:provider", VAULT_TOKEN, monitor.withPrefix("Provider"));
        var providerCredentials = providerPo.execute(cellId);

        // seed provider
        monitor.info("Seeding provider");
        var providerAccessToken = getAccessToken(providerCredentials.clientId(), providerCredentials.clientSecret(), "management-api:write").accessToken();

        var assetId = createAsset(providerCredentials.clientId(), providerAccessToken);
        var policyDefId = createPolicyDef(providerCredentials.clientId(), providerAccessToken);
        createContractDef(providerCredentials.clientId(), providerAccessToken, policyDefId, assetId);
        registerDataplane(providerCredentials.clientId(), providerAccessToken);

        // perform data transfer
        monitor.info("Starting data transfer");
        var catalog = fetchCatalog(consumerCredentials);

        monitor.info("Catalog received, starting data transfer");
        var offerId = catalog.datasets().get(0).offers().get(0).id();
        assertThat(offerId).isNotNull();

        //download dummy data
        var jsonResponse = given()
                .baseUri(BASE_URL)
                .auth().oauth2(getAccessToken(consumerCredentials.clientId(), consumerCredentials.clientSecret(), "management-api:write").accessToken())
                .body("""
                        {
                            "providerId":"did:web:identityhub.edc-v.svc.cluster.local%%3A7083:provider",
                            "policyId": "%s"
                        }
                        """.formatted(offerId))
                .contentType("application/json")
                .post("/cp/api/mgmt/v1alpha/participants/%s/data".formatted(consumerCredentials.clientId()))
                .then()
                .statusCode(200)
                .extract().body().asPrettyString();
        assertThat(jsonResponse).isNotNull();
    }


    private CatalogResponse fetchCatalog(ClientCredentials consumerCredentials) {
        var accessToken = getAccessToken(consumerCredentials.clientId(), consumerCredentials.clientSecret(), "management-api:read");

        return given()
                .baseUri(BASE_URL)
                .auth().oauth2(accessToken.accessToken())
                .contentType("application/json")
                .body("""
                        {
                          "counterPartyDid": "did:web:identityhub.edc-v.svc.cluster.local%3A7083:provider"
                        }
                        """)
                .post("/cp/api/mgmt/v1alpha/participants/%s/catalog".formatted(consumerCredentials.clientId()))
                .then()
                .statusCode(200)
                .extract().body()
                .as(CatalogResponse.class);
    }

    private String createDataspaceProfile() {
        return given()
                .baseUri(TM_BASE_URL)
                .contentType(APPLICATION_JSON)
                .body("""
                        {
                            "artifacts": [],
                            "properties": {}
                        }
                        """)
                .post("/api/v1alpha1/dataspace-profiles")
                .then()
                .statusCode(201)
                .log().ifValidationFails()
                .extract().body().jsonPath().getString("id");
    }

    /**
     * Creates a cell in CFM.
     *
     * @return the Cell ID
     */
    private String getCellId() {
        return given()
                .contentType(APPLICATION_JSON)
                .get(TM_BASE_URL + "/api/v1alpha1/cells")
                .then()
                .statusCode(200)
                .extract().jsonPath().getString("[0].id");
    }


    /**
     * Creates a Common Expression Language (CEL) entry in the control plane
     *
     * @param accessToken OAuth2 token
     */
    private void createCelExpression(String accessToken) {
        var template = loadResourceFile("create_cel_expression.json");

        given()
                .baseUri(BASE_URL)
                .auth().oauth2(accessToken)
                .contentType("application/json")
                .body(template)
                .post("/cp/api/mgmt/v4alpha/celexpressions")
                .then()
                .statusCode(200);
    }

    /**
     * Registers a data plane for a new participant context. This is a bit of a workaround, until Dataplane Signaling is fully implemented.
     * Check also the {@code DataplaneRegistrationApiController} in the {@code extensions/api/mgmt} directory
     *
     * @param participantContextId Participant context for which the data plane should be registered.
     * @param accessToken          OAuth2 token
     */
    private void registerDataplane(String participantContextId, String accessToken) {
        given()
                .baseUri(BASE_URL)
                .contentType(APPLICATION_JSON)
                .auth().oauth2(accessToken)
                .body("""
                        {
                            "allowedSourceTypes": [ "HttpData" ],
                            "allowedTransferTypes": [ "HttpData-PULL" ],
                            "url": "http://dataplane.edc-v.svc.cluster.local:8083/api/control/v1/dataflows"
                        }
                        """)
                .post("/cp/api/mgmt/v4alpha/dataplanes/%s".formatted(participantContextId))
                .then()
                .log().ifValidationFails()
                .statusCode(204);
    }

    private String createAsset(String participantContextId, String accessToken) {
        var template = loadResourceFile("asset.json");
        return given()
                .baseUri(BASE_URL)
                .auth().oauth2(accessToken)
                .contentType("application/json")
                .body(template)
                .post("/cp/api/mgmt/v4alpha/participants/%s/assets".formatted(participantContextId))
                .then()
                .statusCode(200)
                .extract().jsonPath().getString(ID);
    }

    private String createPolicyDef(String participantContextId, String accessToken) {
        var template = loadResourceFile("policy-def.json");
        return given()
                .baseUri(BASE_URL)
                .auth().oauth2(accessToken)
                .contentType("application/json")
                .body(template)
                .post("/cp/api/mgmt/v4alpha/participants/%s/policydefinitions".formatted(participantContextId))
                .then()
                .statusCode(200)
                .extract().jsonPath().getString(ID);
    }

    private String createContractDef(String participantContextId, String accessToken, String policyDefId, String assetId) {
        var template = loadResourceFile("contract-def.json");

        template = template.replace("{{policy_def_id}}", policyDefId);
        template = template.replace("{{asset_id}}", assetId);

        return given()
                .baseUri(BASE_URL)
                .auth().oauth2(accessToken)
                .contentType("application/json")
                .body(template)
                .post("/cp/api/mgmt/v4alpha/participants/%s/contractdefinitions".formatted(participantContextId))
                .then()
                .statusCode(200)
                .extract().jsonPath().getString(ID);
    }
}
