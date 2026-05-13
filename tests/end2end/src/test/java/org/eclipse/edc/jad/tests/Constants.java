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

public interface Constants {

    // make sure that all the following URLs are valid. This is done by port-forwarding the Gateway API Controller (Traefik) port (80) to localhost:8080

    String APPLICATION_JSON = "application/json";
    String TM_BASE_URL = "http://jad.localhost:8080/api/tm";
    String PM_BASE_URL = "http://jad.localhost:8080/api/pm";
    String VAULT_URL = "http://vault.localhost:8080";
    String CONTROLPLANE_BASE_URL = "http://jad.localhost:8080/api/management";
    String SIGLET_BASE_URL = "http://jad.localhost:8080/api/siglet";
    String DATAPLANE_BASE_URL = "http://jad.localhost:8080/";
    String IDENTITYHUB_BASE_URL = "http://jad.localhost:8080/api/identity";
    String KEYCLOAK_URL = "http://jad.localhost:8080/auth";
    String CONTROLPLANE_PROTOCOL_URL = "http://controlplane.edc-v.svc.cluster.local:8082/api/dsp/%s/http-dsp-profile-2025-1";
}
