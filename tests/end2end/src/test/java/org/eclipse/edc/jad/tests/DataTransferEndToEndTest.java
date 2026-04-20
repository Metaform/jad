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
import org.eclipse.edc.connector.controlplane.test.system.utils.client.ManagementApiClientV5;
import org.eclipse.edc.connector.controlplane.test.system.utils.client.api.model.AssetDto;
import org.eclipse.edc.connector.controlplane.test.system.utils.client.api.model.AtomicConstraintDto;
import org.eclipse.edc.connector.controlplane.test.system.utils.client.api.model.CelExpressionDto;
import org.eclipse.edc.connector.controlplane.test.system.utils.client.api.model.ContractDefinitionDto;
import org.eclipse.edc.connector.controlplane.test.system.utils.client.api.model.CriterionDto;
import org.eclipse.edc.connector.controlplane.test.system.utils.client.api.model.DataPlaneRegistrationDto;
import org.eclipse.edc.connector.controlplane.test.system.utils.client.api.model.PermissionDto;
import org.eclipse.edc.connector.controlplane.test.system.utils.client.api.model.PolicyDefinitionDto;
import org.eclipse.edc.connector.controlplane.test.system.utils.client.api.model.PolicyDto;
import org.eclipse.edc.jad.tests.model.ClientCredentials;
import org.eclipse.edc.junit.annotations.EndToEndTest;
import org.eclipse.edc.junit.utils.LazySupplier;
import org.eclipse.edc.spi.monitor.ConsoleMonitor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.edc.jad.tests.Constants.APPLICATION_JSON;
import static org.eclipse.edc.jad.tests.Constants.CONTROLPLANE_BASE_URL;
import static org.eclipse.edc.jad.tests.Constants.CONTROLPLANE_PROTOCOL_URL;
import static org.eclipse.edc.jad.tests.Constants.DATAPLANE_BASE_URL;
import static org.eclipse.edc.jad.tests.Constants.SIGLET_BASE_URL;
import static org.eclipse.edc.jad.tests.Constants.TM_BASE_URL;
import static org.eclipse.edc.jad.tests.KeycloakApi.createKeycloakToken;
import static org.eclipse.edc.jad.tests.KeycloakApi.getAccessToken;

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
    private static final DynamicTokenProvider DYNAMIC_TOKEN_PROVIDER = new DynamicTokenProvider();
    private static final ManagementApiClientV5 MANAGEMENT_API_CLIENT = new ManagementApiClientV5(DYNAMIC_TOKEN_PROVIDER, new LazySupplier<>(() -> URI.create(CONTROLPLANE_BASE_URL + "/api/mgmt")));
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

        DYNAMIC_TOKEN_PROVIDER.setDefaultTokenGenerator(() -> createKeycloakToken("admin", "edc-v-admin-secret", "issuer-admin-api:write", "identity-api:write", "management-api:write", "identity-api:read"));

        createMembershipCelExpression();
        createManufacturerCelExpression();

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
        providerCredentials = providerPo.execute(cellId);

        // onboard manufacturer consumer - only this one will see some assets
        MONITOR.info("Onboarding manufacturer consumer");
        var name = "manufacturer-" + slug;
        var manufacturerContextId = "did:web:identityhub.edc-v.svc.cluster.local%3A7083:" + name;
        var manufacturerPo = new ParticipantOnboarding(name, manufacturerContextId, VAULT_TOKEN, MONITOR.withPrefix("Manufacturer " + slug));
        manufacturerCredentials = manufacturerPo.execute(cellId, "manufacturer");
    }

    private static void createMembershipCelExpression() {
        var expr = "ctx.agent.claims.vc.filter(c, c.type.exists(t, t == 'MembershipCredential')).exists(c, c.credentialSubject.exists(cs, timestamp(cs.membershipStartDate) < now))";
        var celExpression = new CelExpressionDto("MembershipCredential",
                expr,
                Set.of("catalog", "contract.negotiation", "transfer.process"),
                "Expression for evaluating membership credential"
        );
        MANAGEMENT_API_CLIENT.expressions().createExpression(celExpression);
    }

    private static void createManufacturerCelExpression() {
        var expr = "ctx.agent.claims.vc.filter(c, c.type.exists(t, t == 'ManufacturerCredential')).exists(c, c.credentialSubject.exists(cs, timestamp(cs.since) < now))";
        var celExpression = new CelExpressionDto("ManufacturerCredential",
                expr,
                Set.of("catalog", "contract.negotiation", "transfer.process"),
                "Expression for evaluating manufacturer credential"
        );
        MANAGEMENT_API_CLIENT.expressions().createExpression(celExpression);
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
    void testCertDataTransfer() {

        // seed provider
        MONITOR.info("Seeding provider");

        DYNAMIC_TOKEN_PROVIDER.registerTokenGenerator(providerCredentials.clientId(), () -> getAccessToken(providerCredentials.clientId(), providerCredentials.clientSecret(), "management-api:write management-api:read").accessToken());
        DYNAMIC_TOKEN_PROVIDER.registerTokenGenerator(consumerCredentials.clientId(), () -> getAccessToken(consumerCredentials.clientId(), consumerCredentials.clientSecret(), "management-api:write management-api:read").accessToken());

        var assetId = createCertAsset(providerCredentials.clientId());
        var policyDefId = createPolicyDef(providerCredentials.clientId(), "MembershipCredential");
        createContractDef(providerCredentials.clientId(), policyDefId, policyDefId, assetId);
        // Register dataplanes
        registerDataPlane(providerCredentials.clientId());
        registerDataPlane(consumerCredentials.clientId());

        MONITOR.info("starting data transfer");

        var transferId = MANAGEMENT_API_CLIENT.startTransfer(consumerCredentials.clientId(),
                providerCredentials.clientId(), CONTROLPLANE_PROTOCOL_URL.formatted(providerCredentials.clientId()), providerContextId, assetId, "HttpData-PULL");


        MONITOR.info("Fetching siglet token for transferId: " + transferId);

        var transferResponse = given()
                .baseUri(SIGLET_BASE_URL)
                .get("/tokens/%s/%s".formatted(consumerCredentials.clientId(), transferId))
                .then()
                .statusCode(200)
                .extract().body().as(Map.class);

        var accessToken = transferResponse.get("token");

        var list = given()
                .baseUri(DATAPLANE_BASE_URL)
                .header("Authorization", "Bearer " + accessToken)
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

        DYNAMIC_TOKEN_PROVIDER.registerTokenGenerator(providerCredentials.clientId(), () -> getAccessToken(providerCredentials.clientId(), providerCredentials.clientSecret(), "management-api:write management-api:read").accessToken());
        DYNAMIC_TOKEN_PROVIDER.registerTokenGenerator(consumerCredentials.clientId(), () -> getAccessToken(consumerCredentials.clientId(), consumerCredentials.clientSecret(), "management-api:write management-api:read").accessToken());
        DYNAMIC_TOKEN_PROVIDER.registerTokenGenerator(manufacturerCredentials.clientId(), () -> getAccessToken(manufacturerCredentials.clientId(), manufacturerCredentials.clientSecret(), "management-api:write management-api:read").accessToken());

        var assetId = createAsset(providerCredentials.clientId(), "This asset requires the Manufacturer credential to access");
        var accessPolicyId = createPolicyDef(providerCredentials.clientId(), "MembershipCredential");
        var contractPolicyId = createPolicyDef(providerCredentials.clientId(), "ManufacturerCredential");
        createContractDef(providerCredentials.clientId(), accessPolicyId, contractPolicyId, assetId);

        registerDataPlane(providerCredentials.clientId());
        registerDataPlane(consumerCredentials.clientId());
        registerDataPlane(manufacturerCredentials.clientId());

        var negotiationId = MANAGEMENT_API_CLIENT.initContractNegotiation(consumerCredentials.clientId(),
                assetId, CONTROLPLANE_PROTOCOL_URL.formatted(providerCredentials.clientId()), providerContextId);


        MANAGEMENT_API_CLIENT.waitForContractNegotiationState(consumerCredentials.clientId(), negotiationId, "TERMINATED");

        MONITOR.info("starting data transfer");

        var transferId = MANAGEMENT_API_CLIENT.startTransfer(manufacturerCredentials.clientId(),
                providerCredentials.clientId(), CONTROLPLANE_PROTOCOL_URL.formatted(providerCredentials.clientId()), providerContextId, assetId, "HttpData-PULL");


        MONITOR.info("Fetching siglet token for transferId: " + transferId);

        var transferResponse = given()
                .baseUri(SIGLET_BASE_URL)
                .get("/tokens/%s/%s".formatted(manufacturerCredentials.clientId(), transferId))
                .then()
                .statusCode(200)
                .extract().body().as(Map.class);

        var accessToken = transferResponse.get("token");

        var list = given()
                .baseUri(DATAPLANE_BASE_URL)
                .header("Authorization", "Bearer " + accessToken)
                .body("{}")
                .contentType("application/json")
                .post("/app/public/api/data/certs/request")
                .then()
                .statusCode(200)
                .extract().body().as(List.class);

        assertThat(list).isEmpty();

    }

    /**
     * Registers a data plane for a new participant context. This is a bit of a workaround, until Dataplane Signaling is fully implemented.
     * Check also the {@code DataplaneRegistrationApiController} in the {@code extensions/api/mgmt} directory
     *
     * @param participantContextId Participant context for which the data plane should be registered.
     */
    private void registerDataPlane(String participantContextId) {
        MANAGEMENT_API_CLIENT.dataplanes().registerDataPlane(participantContextId, new DataPlaneRegistrationDto(
                "dataplane-%s".formatted(participantContextId),
                "http://siglet.edc-v.svc.cluster.local:8081/api/v1/%s/dataflows".formatted(participantContextId),
                Set.of("HttpData-PULL"),
                Set.of(),
                null
        ));
    }

    private String createAsset(String participantContextId, String description) {
        var properties = new HashMap<String, Object>();
        properties.put("description", description);
        var asset = new AssetDto(properties, null);
        return MANAGEMENT_API_CLIENT.assets().createAsset(participantContextId, asset);
    }

    private String createCertAsset(String participantContextId) {
        return createAsset(participantContextId, "This asset requires the Membership credential to access");
    }

    private String createPolicyDef(String participantContextId, String leftOperand) {
        var constraint = new AtomicConstraintDto(leftOperand, "eq", "active");
        var permission = new PermissionDto(constraint);
        var policy = new PolicyDto(List.of(permission));
        return MANAGEMENT_API_CLIENT.policies().createPolicyDefinition(participantContextId, new PolicyDefinitionDto(policy));
    }

    private String createContractDef(String participantContextId, String accessPolicyId, String contractPolicyId, String assetId) {
        var selector = new CriterionDto("https://w3id.org/edc/v0.0.1/ns/id", "=", assetId);
        var contractDef = new ContractDefinitionDto(accessPolicyId, contractPolicyId, List.of(selector));
        return MANAGEMENT_API_CLIENT.contractDefinitions().createContractDefinition(participantContextId, contractDef);
    }
}
