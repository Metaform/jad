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
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.eclipse.edc.spi.iam.ClaimToken;
import org.eclipse.edc.spi.query.QuerySpec;
import org.eclipse.edc.transaction.spi.TransactionContext;
import org.eclipse.edc.virtualized.dataplane.cert.model.ActivityItem;
import org.eclipse.edc.virtualized.dataplane.cert.model.CertMetadata;
import org.eclipse.edc.virtualized.dataplane.cert.store.CertStore;
import org.jetbrains.annotations.NotNull;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;

import static jakarta.ws.rs.core.Response.Status.FORBIDDEN;
import static jakarta.ws.rs.core.Response.Status.UNAUTHORIZED;
import static org.eclipse.edc.api.authentication.filter.Constants.REQUEST_PROPERTY_CLAIMS;

@Path("certs")
public class CertExchangePublicController {

    private final CertStore certStore;
    private final TransactionContext transactionContext;

    public CertExchangePublicController(CertStore certStore, TransactionContext transactionContext) {
        this.certStore = certStore;
        this.transactionContext = transactionContext;
    }

    @POST
    @Path("/request")
    public List<CertMetadata> queryCertificates(@Context ContainerRequestContext requestContext, QuerySpec querySpec) {
        return transactionContext.execute(() -> {
            checkAuth(requestContext);
            return certStore.queryMetadata(querySpec)
                    // strip out the history for public API
                    .stream().map(ct -> new CertMetadata(ct.id(), ct.contentType(), ct.properties())).toList();
        });
    }

    @GET
    @Path("/{id}")
    public Response certificateDownload(@Context ContainerRequestContext requestContext, @PathParam("id") String id) {
        return transactionContext.execute(() -> {
            var claims = checkAuth(requestContext);
            var metadata = certStore.getMetadata(id);
            if (metadata == null) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            var subject = claims.getClaim("sub");
            if (subject instanceof String s) {
                metadata.history().add(new ActivityItem(s, Instant.now().getEpochSecond(), "DOWNLOAD"));
                certStore.updateMetadata(id, metadata);
                StreamingOutput stream = output -> {
                    try (InputStream is = certStore.retrieve(id)) {
                        is.transferTo(output);
                    }
                };


                return Response.ok(stream)
                        .header("Content-Type", metadata.contentType())
                        .build();
            } else {
                throw new WebApplicationException(FORBIDDEN);
            }

        });
    }

    private ClaimToken checkAuth(ContainerRequestContext requestContext) {
        if (requestContext == null) {
            throw new WebApplicationException(UNAUTHORIZED);
        }
        var claims = requestContext.getProperty(REQUEST_PROPERTY_CLAIMS);
        if (claims instanceof ClaimToken claimToken) {
            return claimToken;
        } else {
            throw new WebApplicationException(FORBIDDEN);
        }
    }


    @NotNull
    protected <T> TypeReference<T> getTypeRef() {
        return new TypeReference<>() {
        };
    }
}
