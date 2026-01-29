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

package org.eclipse.edc.virtualized.dataplane.cert.store;

import org.eclipse.edc.spi.query.QuerySpec;
import org.eclipse.edc.virtualized.dataplane.cert.model.CertMetadata;

import java.io.InputStream;
import java.util.List;

/**
 * Store for data plane certificates.
 */
public interface CertStore {

    /**
     * Stores a certificate along with its metadata.
     *
     * @param id       the unique identifier for the certificate
     * @param metadata the metadata associated with the certificate
     * @param content  the certificate content as a byte array
     */
    void store(String id, CertMetadata metadata, byte[] content);

    /**
     * Deletes a certificate by its unique identifier.
     *
     * @param id the unique identifier of the certificate to delete
     */
    void delete(String id);

    /**
     * Queries certificate metadata based on the provided query specification.
     *
     * @param querySpec the query specification
     * @return a list of certificate metadata matching the query
     */
    List<CertMetadata> queryMetadata(QuerySpec querySpec);

    /**
     * Retrieves the metadata of a certificate by its unique identifier.
     *
     * @param id the unique identifier of the certificate
     * @return the certificate metadata, or null if not found
     */
    CertMetadata getMetadata(String id);

    /**
     * Retrieves the certificate data as an input stream by its unique identifier.
     *
     * @param id the unique identifier of the certificate
     * @return the input stream of the certificate data
     */
    InputStream retrieve(String id);

    /**
     * Overwrites the metadata for a given certificate with the provided metadata.
     */
    void updateMetadata(String id, CertMetadata metadata);
}
