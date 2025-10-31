/*
 *  Copyright (c) 2024 Metaform Systems, Inc.
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

package org.eclipse.edc.virtualized.api.participant.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import org.eclipse.edc.participantcontext.spi.types.ParticipantContext;
import org.eclipse.edc.participantcontext.spi.types.ParticipantContextState;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static java.util.Optional.ofNullable;

/**
 * Manifest (=recipe) for creating the {@link ParticipantContext}.
 */
@JsonDeserialize(builder = ParticipantManifest.Builder.class)
public class ParticipantManifest {
    private boolean isActive;
    private String participantContextId;
    private String participantId;
    private String tokenUrl;
    private String clientId;
    private String clientSecret;

    public String getTokenUrl() {
        return tokenUrl;
    }

    public String getClientId() {
        return clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    private ParticipantManifest() {
    }

    public String getClientSecretAlias(){
        return participantContextId + ".clientSecret";
    }


    /**
     * Indicates whether the participant context should immediately transition to the {@link ParticipantContextState#ACTIVATED} state. This will include
     * publishing the generated DID document.
     */
    public boolean isActive() {
        return isActive;
    }

    /**
     * The DSP {@code participantId} of this participant. This must be a unique and stable ID.
     */
    public String getParticipantContextId() {
        return participantContextId;
    }

    public String getParticipantId() {
        return participantId;
    }

    @JsonPOJOBuilder(withPrefix = "")
    public static final class Builder {

        private final ParticipantManifest manifest;

        private Builder() {
            manifest = new ParticipantManifest();
        }

        @JsonCreator
        public static Builder newInstance() {
            return new Builder();
        }


        public Builder isActive(boolean isActive) {
            manifest.isActive = isActive;
            return this;
        }

        public Builder participantContextId(String participantId) {
            manifest.participantContextId = participantId;
            return this;
        }

        public Builder tokenUrl(String tokenUrl) {
            manifest.tokenUrl = tokenUrl;
            return this;
        }

        public Builder clientId(String clientId) {
            manifest.clientId = clientId;
            return this;
        }

        public Builder clientSecret(String clientSecret) {
            manifest.clientSecret = clientSecret;
            return this;
        }

        public Builder participantId(String participantId) {
            manifest.participantId = participantId;
            return this;
        }

        public ParticipantManifest build() {
            if(manifest.participantContextId == null){
                manifest.participantContextId = UUID.randomUUID().toString();
            }
            return manifest;
        }
    }
}
