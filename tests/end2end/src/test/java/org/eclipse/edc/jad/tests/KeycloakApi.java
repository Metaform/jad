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

public class KeycloakApi {

    static String createKeycloakToken(String clientId, String clientSecret, String... scopes) {
        return getAccessToken(clientId, clientSecret, String.join(" ", scopes)).accessToken();
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
