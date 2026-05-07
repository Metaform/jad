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
import io.restassured.specification.RequestSpecification;
import org.eclipse.edc.connector.controlplane.test.system.utils.client.ManagementApiClientV5;
import org.eclipse.edc.connector.controlplane.test.system.utils.client.api.model.AssetDto;
import org.eclipse.edc.connector.controlplane.test.system.utils.client.api.model.AtomicConstraintDto;
import org.eclipse.edc.connector.controlplane.test.system.utils.client.api.model.ContractDefinitionDto;
import org.eclipse.edc.connector.controlplane.test.system.utils.client.api.model.CriterionDto;
import org.eclipse.edc.connector.controlplane.test.system.utils.client.api.model.DataPlaneRegistrationDto;
import org.eclipse.edc.connector.controlplane.test.system.utils.client.api.model.PermissionDto;
import org.eclipse.edc.connector.controlplane.test.system.utils.client.api.model.PolicyDefinitionDto;
import org.eclipse.edc.connector.controlplane.test.system.utils.client.api.model.PolicyDto;
import org.eclipse.edc.identityhub.spi.keypair.model.KeyPairResource;
import org.eclipse.edc.identityhub.spi.keypair.model.KeyPairState;
import org.eclipse.edc.identityhub.spi.participantcontext.model.KeyDescriptor;
import org.eclipse.edc.jad.tests.model.ClientCredentials;
import org.eclipse.edc.jad.tests.model.ParticipantProfile;
import org.eclipse.edc.junit.annotations.EndToEndTest;
import org.eclipse.edc.junit.utils.LazySupplier;
import org.eclipse.edc.spi.monitor.ConsoleMonitor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.edc.jad.tests.Constants.APPLICATION_JSON;
import static org.eclipse.edc.jad.tests.Constants.CONTROLPLANE_BASE_URL;
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
public class KeyRotationEndToEndTest {
    private static final String VAULT_TOKEN = "root";

    private static final ConsoleMonitor MONITOR = new ConsoleMonitor(ConsoleMonitor.Level.DEBUG, true);
    private static final DynamicTokenProvider TOKEN_PROVIDER = new DynamicTokenProvider();
    private static final ManagementApiClientV5 MANAGEMENT_API_CLIENT = new ManagementApiClientV5(TOKEN_PROVIDER, new LazySupplier<>(() -> URI.create(CONTROLPLANE_BASE_URL)));
    private static ClientCredentials participantCredentials;
    private static String participantIdentifier;

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

        TOKEN_PROVIDER.setDefaultTokenGenerator(() -> createKeycloakToken("admin", "edc-v-admin-secret", "issuer-admin-api:write", "identity-api:write", "management-api:write", "identity-api:read"));

        MONITOR.info("Create cell and dataspace profile");
        var cellId = getCellId();

        // onboard participant
        MONITOR.info("Onboarding participant");
        var providerName = "participant-" + slug;
        participantIdentifier = "did:web:identityhub.edc-v.svc.cluster.local%3A7083:" + providerName;
        var providerPo = new ParticipantOnboarding(providerName, participantIdentifier, VAULT_TOKEN, MONITOR.withPrefix("Participant " + slug), TOKEN_PROVIDER);
        participantCredentials = providerPo.execute(cellId);
    }

    /**
     * Creates a cell in CFM.
     *
     * @return the Cell ID
     */
    public static String getCellId() {
        return TOKEN_PROVIDER.apiRequest()
                .contentType(APPLICATION_JSON)
                .get(TM_BASE_URL + "/cells")
                .then()
                .statusCode(200)
                .extract().jsonPath().getString("[0].id");
    }

    public static RequestSpecification participantRequest() {
        return given()
                .header("Authorization", "Bearer " + TOKEN_PROVIDER.createToken(participantCredentials.clientId(), "participant"));
    }

    @Test
    void testRotateKeys() {

        // seed provider
        MONITOR.info("Seeding provider");
        TOKEN_PROVIDER.registerTokenGenerator(participantCredentials.clientId(), () -> getAccessToken(participantCredentials.clientId(), participantCredentials.clientSecret(),
                "management-api:write management-api:read identity-api:read identity-api:write").accessToken());

        // Register dataplane
        registerDataPlane(participantCredentials.clientId());
        MONITOR.info("starting key rotation process");

        // Query participant profile using the "identifier" (DID)
        var query = """
                {
                   "predicate": "identifier = '%s'"
                }
                """.formatted(participantIdentifier);
        var profiles = TOKEN_PROVIDER.apiRequest()
                .baseUri(TM_BASE_URL)
                .body(query)
                .post("/participant-profiles/query")
                .then()
                .statusCode(200)
                .extract().body().as(ParticipantProfile[].class);
        assertThat(profiles).hasSize(1);

        assertThat(participantIdentifier).isEqualTo(profiles[0].getIdentifier());
        var profileId = profiles[0].getId();

        // no need to obtain the secret, we already have it
        // query the keypair resources next

        var keypairs = participantRequest()
                .baseUri(Constants.IDENTITYHUB_BASE_URL)
                .get("/participants/%s/keypairs".formatted(participantCredentials.clientId()))
                .then()
                .log().ifValidationFails()
                .statusCode(200)
                .extract().body().as(KeyPairResource[].class);

        assertThat(keypairs).isNotEmpty();

        var numKeyPairs = keypairs.length;

        var oldActiveKeyPair = Arrays.stream(keypairs).filter(k -> k.getState() == KeyPairState.ACTIVATED.code()).findFirst().orElseThrow();

        // call the rotation endpoint
        var keyDesc = KeyDescriptor.Builder.newInstance()
                .keyGeneratorParams(Map.of(
                        "algorithm", "eddsa",
                        "curve", "ed25519"
                ))
                .privateKeyAlias(UUID.randomUUID().toString())
                .keyId(participantIdentifier + "#" + UUID.randomUUID())
                .build();
        participantRequest()
                .baseUri(Constants.IDENTITYHUB_BASE_URL)
                .contentType(APPLICATION_JSON)
                .body(keyDesc)
                .post("/participants/%s/keypairs/%s/rotate".formatted(participantCredentials.clientId(), oldActiveKeyPair.getId()))
                .then()
                .log().ifValidationFails()
                .statusCode(204);

        // verify using the keypairs endpoint that and c), the new key is active
        var newKeyPairs = participantRequest()
                .baseUri(Constants.IDENTITYHUB_BASE_URL)
                .get("/participants/%s/keypairs".formatted(participantCredentials.clientId()))
                .then()
                .log().ifValidationFails()
                .statusCode(200)
                .extract().body().as(KeyPairResource[].class);

        MONITOR.info("Verifying new key is active");

        // ... there is now one more key
        assertThat(newKeyPairs).hasSize(numKeyPairs + 1);
        // ... the old key is inactive
        assertThat(Arrays.stream(newKeyPairs).filter(kp -> kp.getId().equals(oldActiveKeyPair.getId())))
                .hasSize(1)
                .allMatch(kp -> kp.getState() == KeyPairState.ROTATED.code());

        // ... the new key is active
        assertThat(Arrays.stream(newKeyPairs).filter(kp -> kp.getKeyId().equals(keyDesc.getKeyId())))
                .hasSize(1)
                .allMatch(kp -> kp.getState() == KeyPairState.ACTIVATED.code());
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
        var asset = new AssetDto(properties, Map.of());
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

    private void createContractDef(String participantContextId, String accessPolicyId, String contractPolicyId, String assetId) {
        var selector = new CriterionDto("https://w3id.org/edc/v0.0.1/ns/id", "=", assetId);
        var contractDef = new ContractDefinitionDto(accessPolicyId, contractPolicyId, List.of(selector));
        MANAGEMENT_API_CLIENT.contractDefinitions().createContractDefinition(participantContextId, contractDef);
    }
}
