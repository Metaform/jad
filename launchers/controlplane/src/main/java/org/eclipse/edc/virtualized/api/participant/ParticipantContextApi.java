package org.eclipse.edc.virtualized.api.participant;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.core.Response;
import org.eclipse.edc.virtualized.api.participant.model.ParticipantManifest;

@OpenAPIDefinition(info = @Info(description = "This is the Identity API for manipulating ParticipantContexts", title = "ParticipantContext Management API", version = "1"))
@Tag(name = "Participant Context")
public interface ParticipantContextApi {

    @Operation(description = "Creates a new ParticipantContext object.",
            operationId = "createParticipant",
            requestBody = @RequestBody(content = @Content(schema = @Schema(implementation = ParticipantManifest.class), mediaType = "application/json")),
            responses = {
                    @ApiResponse(responseCode = "201", description = "The ParticipantContext was created successfully, its API token is returned in the response body."),
                    @ApiResponse(responseCode = "400", description = "Request body was malformed, or the request could not be processed"),
                    @ApiResponse(responseCode = "401", description = "The request could not be completed, because either the authentication was missing or was not valid."),
                    @ApiResponse(responseCode = "409", description = "Can't create the ParticipantContext, because a object with the same ID already exists"),
            }
    )
    Response createParticipant(ParticipantManifest manifest);
}
