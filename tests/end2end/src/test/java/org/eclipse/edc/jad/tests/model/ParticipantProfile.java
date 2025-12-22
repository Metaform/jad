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

package org.eclipse.edc.jad.tests.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ParticipantProfile {
    private Map<String, List<String>> participantRoles = new HashMap<>();
    private String id;
    private int version;
    private String identifier;
    private List<Vpa> vpas = new ArrayList<>();
    private Map<String, Object> properties = new HashMap<>();
    private boolean error;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getIdentifier() {
        return identifier;
    }

    public void setIdentifier(String identifier) {
        this.identifier = identifier;
    }

    public List<Vpa> getVpas() {
        return vpas;
    }

    public void setVpas(List<Vpa> vpas) {
        this.vpas = vpas;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, Object> properties) {
        this.properties = properties;
    }

    public boolean isError() {
        return error;
    }

    public void setError(boolean error) {
        this.error = error;
    }

    public Map<String, List<String>> getParticipantRoles() {
        return participantRoles;
    }

    public void setParticipantRoles(Map<String, List<String>> participantRoles) {
        this.participantRoles = participantRoles;
    }

    public static class Vpa {
        private String id;
        private int version;
        private String state;
        private String stateTimestamp;
        private String type;
        private String cellId;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public int getVersion() {
            return version;
        }

        public void setVersion(int version) {
            this.version = version;
        }

        public String getState() {
            return state;
        }

        public void setState(String state) {
            this.state = state;
        }

        public String getStateTimestamp() {
            return stateTimestamp;
        }

        public void setStateTimestamp(String stateTimestamp) {
            this.stateTimestamp = stateTimestamp;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getCellId() {
            return cellId;
        }

        public void setCellId(String cellId) {
            this.cellId = cellId;
        }
    }
}
