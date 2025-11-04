/*
*  Copyright (c) 2023 Bayerische Motoren Werke Aktiengesellschaft (BMW AG)
*
*  This program and the accompanying materials are made available under the
*  terms of the Apache License, Version 2.0 which is available at
*  https://www.apache.org/licenses/LICENSE-2.0
*
*  SPDX-License-Identifier: Apache-2.0
*
*  Contributors:
*       Bayerische Motoren Werke Aktiengesellschaft (BMW AG) - Initial API and Implementation
*
*/

plugins {
    `java-library`
    id("io.swagger.core.v3.swagger-gradle-plugin")

}

dependencies {
    implementation(libs.edc.spi.participantcontext)
    implementation(libs.edc.core.jersey)
    implementation(libs.edc.core.jetty)
    implementation(libs.edc.spi.web)
    implementation(libs.edc.spi.controlplane)
    implementation(libs.edc.spi.participantcontext.config)
    implementation(libs.edc.spi.dataplane.selector)

    implementation(libs.edc.did.core)
}
