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

import org.eclipse.edc.jad.tests.model.AccessToken;

import static io.restassured.RestAssured.given;
import static org.eclipse.edc.jad.tests.Constants.KEYCLOAK_URL;
import static org.eclipse.edc.jad.tests.DataTransferEndToEndTest.loadResourceFile;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;

public class KeycloakApi {
    private static final String KEYCLOAK_ADMIN_USER = "admin";
    private static final String KEYCLOAK_ADMIN_PASSWORD = "admin";

    static void createKeycloakUser(String name, String clientId, String secret, String role, String token) {
        var template = loadResourceFile("create_keycloak_user.json");
        template = template
                .replace("{{issuer_name}}", name)
                .replace("{{issuer_clientId}}", clientId)
                .replace("{{issuer_clientSecret}}", secret)
                .replace("{{role}}", role);

        given()
                .baseUri(KEYCLOAK_URL)
                .contentType("application/json")
                .auth().oauth2(token)
                .body(template)
                .post("/admin/realms/edcv/clients")
                .then()
                .log().ifError()
                .statusCode(anyOf(equalTo(201), equalTo(409)));

    }

    static String createKeycloakToken(String clientId, String clientSecret, String... scopes) {
        return getAccessToken(clientId, clientSecret, String.join(" ", scopes)).accessToken();
    }

    static String createKeycloakAdminToken() {
        var at = given()
                .baseUri(KEYCLOAK_URL)
                .contentType("application/x-www-form-urlencoded")
                .formParam("username", KEYCLOAK_ADMIN_USER)
                .formParam("password", KEYCLOAK_ADMIN_PASSWORD)
                .formParam("client_id", "admin-cli")
                .formParam("grant_type", "password")
                .post("/realms/master/protocol/openid-connect/token")
                .then()
                .statusCode(200)
                .extract()
                .body()
                .as(AccessToken.class);
        return at.accessToken();
    }

    static AccessToken getAccessToken(String clientId, String clientSecret, String scope) {
        return given()
                .baseUri(KEYCLOAK_URL)
                .contentType("application/x-www-form-urlencoded")
                .formParam("client_id", clientId)
                .formParam("client_secret", clientSecret)
                .formParam("grant_type", "client_credentials")
                .formParam("scope", scope)
                .post("/realms/edcv/protocol/openid-connect/token")
                .then()
                .statusCode(200)
                .extract()
                .body()
                .as(AccessToken.class);
    }
}
