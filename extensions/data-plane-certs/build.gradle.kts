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

plugins {
    `java-library`
    id(libs.plugins.swagger.get().pluginId)
}

dependencies {
    api(libs.edc.spi.http)
    api(libs.edc.spi.transaction)
    api(libs.edc.spi.web)
    implementation(libs.edc.core.boot)
    implementation(libs.edc.core.runtime)
    implementation(libs.edc.core.token)
    implementation(libs.edc.core.connector)
    implementation(libs.edc.core.api)
    implementation(libs.edc.core.participantcontext.config)
    implementation(libs.jersey.multipart)
    implementation(libs.edc.lib.util)
    implementation(libs.edc.lib.sql)
    implementation(libs.edc.lib.token)
    implementation(libs.edc.lib.oauth2.authn)
    implementation(libs.edc.core.sql.bootstrapper)
    implementation(libs.edc.core.sql)
    implementation(libs.edc.core.http)
    implementation(libs.edc.transaction.local)
    implementation(libs.edc.transaction.pool)
    implementation(libs.edc.api.control.configuration)
    implementation(libs.edc.api.observability)
    implementation(libs.jakarta.rsApi)
    implementation(libs.postgres)

    testImplementation(libs.edc.lib.http)
    testImplementation(libs.edc.junit)
    testImplementation(libs.restAssured)
    testImplementation(testFixtures(libs.edc.core.jersey))

}
edcBuild {
    swagger {
        apiGroup.set("public-api")
    }
}


