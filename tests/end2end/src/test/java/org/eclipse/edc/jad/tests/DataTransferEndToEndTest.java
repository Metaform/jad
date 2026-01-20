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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.RestAssured;
import io.restassured.config.ObjectMapperConfig;
import io.restassured.config.RestAssuredConfig;
import org.eclipse.edc.jad.tests.model.CatalogResponse;
import org.eclipse.edc.jad.tests.model.ClientCredentials;
import org.eclipse.edc.junit.annotations.EndToEndTest;
import org.eclipse.edc.spi.monitor.ConsoleMonitor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.edc.jad.tests.Constants.APPLICATION_JSON;
import static org.eclipse.edc.jad.tests.Constants.CONTROLPLANE_BASE_URL;
import static org.eclipse.edc.jad.tests.Constants.DATAPLANE_BASE_URL;
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

    private static final ConsoleMonitor MONITOR = new ConsoleMonitor(ConsoleMonitor.Level.DEBUG, true);
    private static ClientCredentials providerCredentials;
    private static ClientCredentials consumerCredentials;
    private static String providerContextId;
    private static ClientCredentials manufacturerCredentials;


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

    @BeforeAll
    static void prepare() {
        // globally disable failing on unknown properties for RestAssured
        RestAssured.config = RestAssuredConfig.config().objectMapperConfig(new ObjectMapperConfig().jackson2ObjectMapperFactory(
                (cls, charset) -> {
                    ObjectMapper om = new ObjectMapper().findAndRegisterModules();
                    om.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                    return om;
                }
        ));

        var slug = Instant.now().getEpochSecond();

        var adminToken = createKeycloakToken("admin", "edc-v-admin-secret", "issuer-admin-api:write", "identity-api:write", "management-api:write", "identity-api:read");
        createCelExpression(adminToken, "membership_cel_expression.json");
        createCelExpression(adminToken, "manufacturer_cel_expression.json");

        MONITOR.info("Create cell and dataspace profile");
        var cellId = getCellId();

        // onboard consumer
        MONITOR.info("Onboarding (standard) consumer");
        var consumerName = "consumer-" + slug;
        var consumerContextId = "did:web:identityhub.edc-v.svc.cluster.local%3A7083:" + consumerName;
        var po = new ParticipantOnboarding(consumerName, consumerContextId, VAULT_TOKEN, MONITOR.withPrefix("Consumer " + slug));
        consumerCredentials = po.execute(cellId);

        // onboard provider
        MONITOR.info("Onboarding provider");
        var providerName = "provider-" + slug;
        providerContextId = "did:web:identityhub.edc-v.svc.cluster.local%3A7083:" + providerName;
        var providerPo = new ParticipantOnboarding(providerName, providerContextId, VAULT_TOKEN, MONITOR.withPrefix("Provider " + slug));
        providerCredentials = providerPo.execute(cellId, "manufacturer");

        // onboard manufacturer consumer - only this one will see some assets
        MONITOR.info("Onboarding manufacturer consumer");
        var name = "manufacturer-" + slug;
        var manufacturerContextId = "did:web:identityhub.edc-v.svc.cluster.local%3A7083:" + name;
        var manufacturerPo = new ParticipantOnboarding(name, manufacturerContextId, VAULT_TOKEN, MONITOR.withPrefix("Manufacturer " + slug));
        manufacturerCredentials = manufacturerPo.execute(cellId, "manufacturer");
    }

    /**
     * Creates a Common Expression Language (CEL) entry in the control plane
     *
     * @param accessToken  OAuth2 token
     * @param resourceName name of the resource file that contains the CEL expression.
     */
    private static void createCelExpression(String accessToken, String resourceName) {
        var template = loadResourceFile(resourceName);

        given()
                .baseUri(CONTROLPLANE_BASE_URL)
                .auth().oauth2(accessToken)
                .contentType("application/json")
                .body(template)
                .post("/api/mgmt/v4alpha/celexpressions")
                .then()
                .statusCode(200);
    }

    /**
     * Creates a cell in CFM.
     *
     * @return the Cell ID
     */
    private static String getCellId() {
        return given()
                .contentType(APPLICATION_JSON)
                .get(TM_BASE_URL + "/api/v1alpha1/cells")
                .then()
                .statusCode(200)
                .extract().jsonPath().getString("[0].id");
    }

    @Test
    void testTodoDataTransfer() {

        // seed provider
        MONITOR.info("Seeding provider");
        var providerAccessToken = getAccessToken(providerCredentials.clientId(), providerCredentials.clientSecret(), "management-api:write").accessToken();

        var assetId = createAsset(providerCredentials.clientId(), providerAccessToken, "asset.json");
        var policyDefId = createPolicyDef(providerCredentials.clientId(), providerAccessToken, "policy-def.json");
        createContractDef(providerCredentials.clientId(), providerAccessToken, policyDefId, policyDefId, assetId);
        registerDataPlane(providerCredentials.clientId(), providerAccessToken);

        // perform data transfer
        MONITOR.info("Starting data transfer");
        var catalog = fetchCatalog(consumerCredentials);

        MONITOR.info("Catalog received, starting data transfer");
        var offerId = catalog.datasets().stream().filter(dataSet -> dataSet.id().equals(assetId)).findFirst().get().offers().get(0).id();
        assertThat(offerId).isNotNull();

        //download dummy data
        var jsonResponse = given()
                .baseUri(CONTROLPLANE_BASE_URL)
                .auth().oauth2(getAccessToken(consumerCredentials.clientId(), consumerCredentials.clientSecret(), "management-api:write").accessToken())
                .body("""
                        {
                            "providerId":"%s",
                            "policyId": "%s"
                        }
                        """.formatted(providerContextId, offerId))
                .contentType("application/json")
                .post("/api/mgmt/v1alpha/participants/%s/data".formatted(consumerCredentials.clientId()))
                .then()
                .statusCode(200)
                .extract().body().asPrettyString();
        assertThat(jsonResponse).isNotNull();
    }

    @Test
    void testCertDataTransfer() {

        // seed provider
        MONITOR.info("Seeding provider");
        var providerAccessToken = getAccessToken(providerCredentials.clientId(), providerCredentials.clientSecret(), "management-api:write").accessToken();

        var assetId = createCertAsset(providerCredentials.clientId(), providerAccessToken);
        var policyDefId = createPolicyDef(providerCredentials.clientId(), providerAccessToken, "policy-def.json");
        createContractDef(providerCredentials.clientId(), providerAccessToken, policyDefId, policyDefId, assetId);
        registerDataPlane(providerCredentials.clientId(), providerAccessToken);

        // perform data transfer
        MONITOR.info("Starting data transfer");
        var catalog = fetchCatalog(consumerCredentials);

        MONITOR.info("Catalog received, starting data transfer");
        var offerId = catalog.datasets().stream().filter(dataSet -> dataSet.id().equals(assetId)).findFirst().get().offers().get(0).id();
        assertThat(offerId).isNotNull();

        // trigger transfer
        var transferResponse = given()
                .baseUri(CONTROLPLANE_BASE_URL)
                .auth().oauth2(getAccessToken(consumerCredentials.clientId(), consumerCredentials.clientSecret(), "management-api:write").accessToken())
                .body("""
                        {
                            "providerId":"%s",
                            "policyId": "%s"
                        }
                        """.formatted(providerContextId, offerId))
                .contentType("application/json")
                .post("/api/mgmt/v1alpha/participants/%s/transfer".formatted(consumerCredentials.clientId()))
                .then()
                .statusCode(200)
                .extract().body().as(Map.class);

        var accessToken = transferResponse.get("https://w3id.org/edc/v0.0.1/ns/authorization");

        var list = given()
                .baseUri(DATAPLANE_BASE_URL)
                .header("Authorization", accessToken)
                .body("{}")
                .contentType("application/json")
                .post("/app/public/api/data/certs/request")
                .then()
                .statusCode(200)
                .extract().body().as(List.class);

        assertThat(list).isEmpty();
    }

    @Test
    void testTransferLimitedAccess() {
        // seed provider
        MONITOR.info("Seeding provider");
        var providerAccessToken = getAccessToken(providerCredentials.clientId(), providerCredentials.clientSecret(), "management-api:write").accessToken();

        var assetId = createAsset(providerCredentials.clientId(), providerAccessToken, "asset-restricted.json");
        var accessPolicyId = createPolicyDef(providerCredentials.clientId(), providerAccessToken, "policy-def.json");
        var contractPolicyId = createPolicyDef(providerCredentials.clientId(), providerAccessToken, "policy-def-manufacturer.json");
        createContractDef(providerCredentials.clientId(), providerAccessToken, accessPolicyId, contractPolicyId, assetId);
        registerDataPlane(providerCredentials.clientId(), providerAccessToken);

        // perform data transfer
        MONITOR.info("Starting data transfer");
        var catalog = fetchCatalog(consumerCredentials);

        MONITOR.info("Catalog received, starting data transfer");
        var offerId = catalog.datasets().stream().filter(dataSet -> dataSet.id().equals(assetId)).findFirst().get().offers().get(0).id();
        assertThat(offerId).isNotNull();


        // attempt download as a normal consumer - should fail due to missing credentials
        given()
                .baseUri(CONTROLPLANE_BASE_URL)
                .auth().oauth2(getAccessToken(consumerCredentials.clientId(), consumerCredentials.clientSecret(), "management-api:write").accessToken())
                .body("""
                        {
                            "providerId":"%s",
                            "policyId": "%s",
                            "policyType": "manufacturer"
                        }
                        """.formatted(providerContextId, offerId))
                .contentType("application/json")
                .post("/api/mgmt/v1alpha/participants/%s/data".formatted(consumerCredentials.clientId()))
                .then()
                .statusCode(500);

        // download the asset as manufacturer - should work because the manufacturer has the necessary credentials
        given()
                .baseUri(CONTROLPLANE_BASE_URL)
                .auth().oauth2(getAccessToken(manufacturerCredentials.clientId(), manufacturerCredentials.clientSecret(), "management-api:write").accessToken())
                .body("""
                        {
                            "providerId":"%s",
                            "policyId": "%s",
                            "policyType": "manufacturer"
                        }
                        """.formatted(providerContextId, offerId))
                .contentType("application/json")
                .post("/api/mgmt/v1alpha/participants/%s/data".formatted(manufacturerCredentials.clientId()))
                .then()
                .statusCode(200);

    }

    private CatalogResponse fetchCatalog(ClientCredentials consumerCredentials) {
        var accessToken = getAccessToken(consumerCredentials.clientId(), consumerCredentials.clientSecret(), "management-api:read");

        return given()
                .baseUri(CONTROLPLANE_BASE_URL)
                .auth().oauth2(accessToken.accessToken())
                .contentType("application/json")
                .body("""
                        {
                          "counterPartyDid": "%s"
                        }
                        """.formatted(providerContextId))
                .post("/api/mgmt/v1alpha/participants/%s/catalog".formatted(consumerCredentials.clientId()))
                .then()
                .statusCode(200)
                .extract().body()
                .as(CatalogResponse.class);
    }

    /**
     * Registers a data plane for a new participant context. This is a bit of a workaround, until Dataplane Signaling is fully implemented.
     * Check also the {@code DataplaneRegistrationApiController} in the {@code extensions/api/mgmt} directory
     *
     * @param participantContextId Participant context for which the data plane should be registered.
     * @param accessToken          OAuth2 token
     */
    private void registerDataPlane(String participantContextId, String accessToken) {
        given()
                .baseUri(CONTROLPLANE_BASE_URL)
                .contentType(APPLICATION_JSON)
                .auth().oauth2(accessToken)
                .body("""
                        {
                            "allowedSourceTypes": [ "HttpData", "HttpCertData" ],
                            "allowedTransferTypes": [ "HttpData-PULL" ],
                            "url": "http://dataplane.edc-v.svc.cluster.local:8083/api/control/v1/dataflows"
                        }
                        """)
                .post("/api/mgmt/v4alpha/dataplanes/%s".formatted(participantContextId))
                .then()
                .log().ifValidationFails()
                .statusCode(204);
    }

    private String createAsset(String participantContextId, String accessToken, String resourceName) {
        var template = loadResourceFile(resourceName);
        return given()
                .baseUri(CONTROLPLANE_BASE_URL)
                .auth().oauth2(accessToken)
                .contentType("application/json")
                .body(template)
                .post("/api/mgmt/v4alpha/participants/%s/assets".formatted(participantContextId))
                .then()
                .statusCode(200)
                .extract().jsonPath().getString(ID);
    }

    private String createCertAsset(String participantContextId, String accessToken) {
        return createAsset(participantContextId, accessToken, "asset-cert.json");
    }

    private String createPolicyDef(String participantContextId, String accessToken, String resourceName) {
        var template = loadResourceFile(resourceName);
        return given()
                .baseUri(CONTROLPLANE_BASE_URL)
                .auth().oauth2(accessToken)
                .contentType("application/json")
                .body(template)
                .post("/api/mgmt/v4alpha/participants/%s/policydefinitions".formatted(participantContextId))
                .then()
                .statusCode(200)
                .extract().jsonPath().getString(ID);
    }

    private String createContractDef(String participantContextId, String accessToken, String accessPolicyId, String contractPolicyId, String assetId) {
        var template = loadResourceFile("contract-def.json");

        template = template.replace("{{access_policy_id}}", accessPolicyId);
        template = template.replace("{{contract_policy_id}}", contractPolicyId);
        template = template.replace("{{asset_id}}", assetId);

        return given()
                .baseUri(CONTROLPLANE_BASE_URL)
                .auth().oauth2(accessToken)
                .contentType("application/json")
                .body(template)
                .post("/api/mgmt/v4alpha/participants/%s/contractdefinitions".formatted(participantContextId))
                .then()
                .statusCode(200)
                .extract().jsonPath().getString(ID);
    }
}
