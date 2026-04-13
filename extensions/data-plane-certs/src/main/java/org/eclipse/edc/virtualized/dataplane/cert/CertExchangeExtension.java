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

package org.eclipse.edc.virtualized.dataplane.cert;

import org.eclipse.edc.api.authentication.JwksResolver;
import org.eclipse.edc.api.authentication.filter.JwtValidatorFilter;
import org.eclipse.edc.keys.spi.KeyParserRegistry;
import org.eclipse.edc.runtime.metamodel.annotation.Configuration;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Setting;
import org.eclipse.edc.runtime.metamodel.annotation.Settings;
import org.eclipse.edc.spi.EdcException;
import org.eclipse.edc.spi.system.Hostname;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.eclipse.edc.token.rules.ExpirationIssuedAtValidationRule;
import org.eclipse.edc.token.rules.IssuerEqualsValidationRule;
import org.eclipse.edc.token.rules.NotBeforeValidationRule;
import org.eclipse.edc.token.spi.TokenValidationRule;
import org.eclipse.edc.token.spi.TokenValidationService;
import org.eclipse.edc.transaction.spi.TransactionContext;
import org.eclipse.edc.virtualized.dataplane.cert.api.CertExchangePublicController;
import org.eclipse.edc.virtualized.dataplane.cert.api.CertInternalExchangeController;
import org.eclipse.edc.virtualized.dataplane.cert.store.CertStore;
import org.eclipse.edc.web.spi.WebService;
import org.eclipse.edc.web.spi.configuration.PortMapping;
import org.eclipse.edc.web.spi.configuration.PortMappingRegistry;

import java.net.MalformedURLException;
import java.net.URL;
import java.time.Clock;
import java.util.List;

import static org.eclipse.edc.virtualized.dataplane.cert.CertExchangeExtension.NAME;

@Extension(NAME)
public class CertExchangeExtension implements ServiceExtension {
    public static final String NAME = "Cert Exchange Extension";
    public static final String API_CONTEXT = "certs";
    private static final int DEFAULT_CERTS_PORT = 8186;
    private static final String DEFAULT_CERTS_PATH = "/api/data";
    private static final long FIVE_MINUTES = 1000 * 60 * 5;

    @Configuration
    private CertApiConfiguration apiConfiguration;

    @Inject
    private Hostname hostname;

    @Inject
    private PortMappingRegistry portMappingRegistry;

    @Inject
    private WebService webService;

    @Inject
    private CertStore certStore;

    @Inject
    private TransactionContext transactionContext;

    @Inject
    private TokenValidationService tokenValidationService;

    @Configuration
    private SigletConfig sigletConfig;

    @Inject
    private KeyParserRegistry keyParserRegistry;

    @Inject
    private Clock clock;

    @Override
    public void initialize(ServiceExtensionContext context) {
        var portMapping = new PortMapping(API_CONTEXT, apiConfiguration.port(), apiConfiguration.path());
        portMappingRegistry.register(portMapping);

        URL url;
        try {
            url = new URL(sigletConfig.jwksUrl());
        } catch (MalformedURLException e) {
            throw new EdcException(e);
        }

        webService.registerResource(API_CONTEXT, new CertExchangePublicController(certStore, transactionContext));
        webService.registerResource(API_CONTEXT, new JwtValidatorFilter(tokenValidationService, new JwksResolver(url, keyParserRegistry, sigletConfig.cacheValidityInMillis), getRules()));

        webService.registerResource("control", new CertInternalExchangeController(certStore, transactionContext));

    }

    private List<TokenValidationRule> getRules() {
        return List.of(
                new IssuerEqualsValidationRule(sigletConfig.expectedIssuer),
                new NotBeforeValidationRule(clock, 0, true),
                new ExpirationIssuedAtValidationRule(clock, 0, false)
        );
    }


    @Settings
    record CertApiConfiguration(
            @Setting(key = "web.http." + API_CONTEXT + ".port", description = "Port for " + API_CONTEXT + " api context", defaultValue = DEFAULT_CERTS_PORT + "")
            int port,
            @Setting(key = "web.http." + API_CONTEXT + ".path", description = "Path for " + API_CONTEXT + " api context", defaultValue = DEFAULT_CERTS_PATH)
            String path
    ) {

    }

    @Settings
    record SigletConfig(
            @Setting(key = "edc.iam.siglet.issuer", description = "Issuer of the Siglet server", required = false)
            String expectedIssuer,
            @Setting(key = "edc.iam.siglet.jwks.url", description = "Absolute URL where the JWKS of the Siglet server is hosted")
            String jwksUrl,
            @Setting(key = "edc.iam.siglet.jwks.cache.validity", description = "Time (in ms) that cached JWKS are cached", defaultValue = "" + FIVE_MINUTES)
            long cacheValidityInMillis
    ) {

    }
}
