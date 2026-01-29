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

package org.eclipse.edc.virtualized.dataplane.cert.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.nimbusds.jwt.SignedJWT;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.eclipse.edc.connector.dataplane.spi.iam.DataPlaneAuthorizationService;
import org.eclipse.edc.spi.query.QuerySpec;
import org.eclipse.edc.transaction.spi.TransactionContext;
import org.eclipse.edc.virtualized.dataplane.cert.model.ActivityItem;
import org.eclipse.edc.virtualized.dataplane.cert.model.CertMetadata;
import org.eclipse.edc.virtualized.dataplane.cert.store.CertStore;
import org.jetbrains.annotations.NotNull;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static jakarta.ws.rs.core.HttpHeaders.AUTHORIZATION;
import static jakarta.ws.rs.core.Response.Status.FORBIDDEN;
import static jakarta.ws.rs.core.Response.Status.UNAUTHORIZED;

@Path("certs")
public class CertExchangePublicController {

    private final DataPlaneAuthorizationService authorizationService;
    private final CertStore certStore;
    private final TransactionContext transactionContext;

    public CertExchangePublicController(DataPlaneAuthorizationService authorizationService, CertStore certStore, TransactionContext transactionContext) {
        this.authorizationService = authorizationService;
        this.certStore = certStore;
        this.transactionContext = transactionContext;
    }

    @POST
    @Path("/request")
    public List<CertMetadata> queryCertificates(@HeaderParam(AUTHORIZATION) String token, QuerySpec querySpec) {
        return transactionContext.execute(() -> {
            checkAuth(token);
            return certStore.queryMetadata(querySpec)
                    // strip out the history for public API
                    .stream().map(ct -> new CertMetadata(ct.id(), ct.contentType(), ct.properties())).toList();
        });
    }

    @GET
    @Path("/{id}")
    public Response certificateDownload(@HeaderParam(AUTHORIZATION) String token, @PathParam("id") String id) {
        return transactionContext.execute(() -> {
            var subject = checkAuth(token);
            var metadata = certStore.getMetadata(id);
            if (metadata == null) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            metadata.history().add(new ActivityItem(subject, Instant.now().getEpochSecond(), "DOWNLOAD"));
            certStore.updateMetadata(id, metadata);
            StreamingOutput stream = output -> {
                try (InputStream is = certStore.retrieve(id)) {
                    is.transferTo(output);
                }
            };


            return Response.ok(stream)
                    .header("Content-Type", metadata.contentType())
                    .build();
        });
    }

    private String checkAuth(String token) {
        if (token == null) {
            throw new WebApplicationException(UNAUTHORIZED);
        }

        // assuming "token" is a JWT, lets parse it and extract the `sub` claim
        var subject = parseJwt(token);


        var sourceDataAddress = authorizationService.authorize(token, Map.of());
        if (sourceDataAddress.failed()) {
            throw new WebApplicationException(FORBIDDEN);

        }

        return subject;
    }

    private String parseJwt(String token) {
        try {
            var signedJwt = SignedJWT.parse(token);
            return String.join(",", signedJwt.getJWTClaimsSet().getAudience());
        } catch (Exception e) {
            return null;
        }
    }

    @NotNull
    protected <T> TypeReference<T> getTypeRef() {
        return new TypeReference<>() {
        };
    }
}
