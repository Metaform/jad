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
    String TM_BASE_URL = "http://tm.localhost:8080";
    String PM_BASE_URL = "http://pm.localhost:8080";
    String VAULT_URL = "http://vault.localhost:8080";
    String CONTROLPLANE_BASE_URL = "http://cp.localhost:8080";
    String SIGLET_BASE_URL = "http://siglet.localhost:8080";
    String DATAPLANE_BASE_URL = "http://dp.localhost:8080";
    String IDENTITYHUB_BASE_URL = "http://ih.localhost:8080";
    String KEYCLOAK_URL = "http://keycloak.localhost:8080";
    String CONTROLPLANE_PROTOCOL_URL = "http://controlplane.edc-v.svc.cluster.local:8082/api/dsp/%s/2025-1";
}
