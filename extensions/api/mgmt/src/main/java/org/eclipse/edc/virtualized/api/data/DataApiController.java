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

package org.eclipse.edc.virtualized.api.data;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.container.Suspended;
import jakarta.ws.rs.core.Response;
import org.eclipse.edc.connector.controlplane.services.spi.catalog.CatalogService;
import org.eclipse.edc.iam.did.spi.resolution.DidResolverRegistry;
import org.eclipse.edc.participantcontext.spi.service.ParticipantContextService;
import org.eclipse.edc.spi.EdcException;
import org.eclipse.edc.spi.response.StatusResult;
import org.eclipse.edc.virtualized.service.DataRequestService;
import org.eclipse.edc.web.spi.exception.BadGatewayException;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

/**
 * This controller is a quick workaround so that we are able to get a catalog from a counter-party, and to trigger the all-in-one data transfer.
 * see {@link DataRequestService} for more details.
 */
@Consumes(APPLICATION_JSON)
@Produces(APPLICATION_JSON)
@Path("/v1alpha/participants/{participantContextId}/")
public class DataApiController {

    private final CatalogService service;
    private final DidResolverRegistry didResolverRegistry;
    private final ParticipantContextService participantContextService;
    private final DataRequestService dataRequestService;

    public DataApiController(CatalogService service, DidResolverRegistry didResolverRegistry, ParticipantContextService participantContextService, DataRequestService dataRequestService) {
        this.service = service;
        this.didResolverRegistry = didResolverRegistry;
        this.participantContextService = participantContextService;
        this.dataRequestService = dataRequestService;
    }


    @POST
    @Path("/catalog")
    public void getCatalog(@PathParam("participantContextId") String participantContextId,
                           CatalogRequest catalogRequest,
                           @Suspended AsyncResponse response) {

        var participantContext = participantContextService.getParticipantContext(participantContextId);
        if (participantContext.failed()) {
            response.resume(Response.status(404).entity("Participant context '%s' not found".formatted(participantContextId)).build());
        }

        var counterPartyDid = catalogRequest.getCounterPartyDid();
        var did = didResolverRegistry.resolve(counterPartyDid);
        if (did.failed()) {
            response.resume(Response.status(400).entity("Could not resolve DID '%s': %s".formatted(counterPartyDid, did.getFailureDetail())).build());
        }

        var doc = did.getContent();

        var protocolEndpoint = doc.getService().stream().filter(s -> s.getType().equals("ProtocolEndpoint")).findFirst();
        if (protocolEndpoint.isEmpty()) {
            response.resume(Response.status(400).entity("No ProtocolEndpoint service found in DID Document for '%s'".formatted(counterPartyDid)).build());
            return;
        }
        var counterPartyAddress = protocolEndpoint.get().getServiceEndpoint();
        var catalog = service.requestCatalog(participantContext.getContent(), counterPartyDid, counterPartyAddress, catalogRequest.getProtocol(), catalogRequest.getQuery());

        catalog.whenComplete((result, throwable) -> {
            try {
                response.resume(toResponse(result, throwable));
            } catch (Throwable mapped) {
                response.resume(mapped);
            }
        });

    }

    @POST
    @Path("/transfer")
    public void setupTransfer(@PathParam("participantContextId") String participantContextId, DataRequest dataRequest, @Suspended AsyncResponse response) {
        var participantContext = participantContextService.getParticipantContext(participantContextId);
        if (participantContext.failed()) {
            response.resume(Response.status(404).entity("Participant context '%s' not found".formatted(participantContextId)).build());
        }
        dataRequestService.setupTransfer(participantContext.getContent(), dataRequest)
                .whenComplete((result, throwable) -> {
                    try {
                        if (throwable != null) {
                            response.resume(Response.status(500).entity(throwable.getMessage()).build());

                        } else if (result.succeeded()) {
                            response.resume(result.getContent());
                        } else {
                            response.resume(Response.status(500).entity(result.getFailureDetail()).build());
                        }
                    } catch (Throwable mapped) {
                        response.resume(Response.status(500).entity(mapped.getMessage()).build());
                    }
                });
    }

    @GET
    @Path("/edr/{transferProcessId}")
    public void getEdr(@PathParam("participantContextId") String participantContextId, @PathParam("transferProcessId") String transferProcessId, @Suspended AsyncResponse response) {
        var participantContext = participantContextService.getParticipantContext(participantContextId);
        if (participantContext.failed()) {
            response.resume(Response.status(404).entity("Participant context '%s' not found".formatted(participantContextId)).build());
        }
        dataRequestService.getEdr(transferProcessId)
                .whenComplete((result, throwable) -> {
                    try {
                        if (throwable != null) {
                            response.resume(throwable);
                        }
                        response.resume(result);
                    } catch (Throwable mapped) {
                        response.resume(mapped);
                    }
                });
    }

    private <T> T toResponse(StatusResult<T> result, Throwable throwable) throws Throwable {
        if (throwable == null) {
            if (result.succeeded()) {
                return result.getContent();
            } else {
                throw new BadGatewayException(result.getFailureDetail());
            }
        } else {
            if (throwable instanceof EdcException || throwable.getCause() instanceof EdcException) {
                throw new BadGatewayException(throwable.getMessage());
            } else {
                throw throwable;
            }
        }
    }

}
