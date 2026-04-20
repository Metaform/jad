/*
 *  Copyright (c) 2026 Metaform Systems, Inc.
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

import org.eclipse.edc.api.authentication.OauthTokenProvider;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public class DynamicTokenProvider implements OauthTokenProvider {

    private final Map<String, Supplier<String>> tokenGenerators = new ConcurrentHashMap<>();
    public Supplier<String> defaultTokenGenerator;

    @Override
    public String createToken(String participantContextId, String role) {
        if (participantContextId == null) {
            if (defaultTokenGenerator == null) {
                throw new IllegalStateException("No default token generator registered");
            }
            return defaultTokenGenerator.get();
        } else {
            return Objects.requireNonNull(tokenGenerators.get(participantContextId)).get();
        }
    }

    public void registerTokenGenerator(String participantContextId, Supplier<String> tokenGenerator) {
        tokenGenerators.put(participantContextId, tokenGenerator);
    }

    public void setDefaultTokenGenerator(Supplier<String> defaultTokenGenerator) {
        this.defaultTokenGenerator = defaultTokenGenerator;
    }
}
