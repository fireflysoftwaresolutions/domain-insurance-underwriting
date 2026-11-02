/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.domain.insurance.underwriting.web.controllers;

import io.fireflysoftware.core.insurance.underwriting.sdk.model.*;
import io.fireflysoftware.domain.insurance.underwriting.core.services.staff.GovernedStaffUnderwritingService;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.*;
import io.swagger.v3.oas.annotations.security.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import org.fireflyframework.web.idempotency.annotation.DisableIdempotency;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@RestController
@Validated
@RequestMapping("/api/v1/staff/underwriting")
@Tag(name="StaffUnderwriting",description="Staff referral creation, independent approval, decision and original-action recovery through the Core owner.")
@SecurityScheme(name="staffUnderwritingBearer",type=SecuritySchemeType.HTTP,scheme="bearer",bearerFormat="JWT")
@SecurityRequirement(name="staffUnderwritingBearer")
public final class StaffUnderwritingController {
    private final ObjectProvider<GovernedStaffUnderwritingService> services;
    public StaffUnderwritingController(ObjectProvider<GovernedStaffUnderwritingService> services){this.services=services;}

    @DisableIdempotency @PostMapping("/intents")
    @Operation(operationId="prepareStaffUnderwritingIntent",summary="Prepare an original staff operation without mutating business data")
    @ApiResponse(responseCode="200",content=@Content(mediaType=MediaType.APPLICATION_JSON_VALUE,schema=@Schema(implementation=GovernedUnderwritingPreparedIntent.class)))
    public Mono<ResponseEntity<GovernedUnderwritingPreparedIntent>> prepare(
        @Parameter(hidden=true) @RequestHeader(name=HttpHeaders.AUTHORIZATION,required=false) String authorization,
        @Valid @RequestBody GovernedUnderwritingIntent intent){return Mono.defer(()->service().prepare(authorization,intent)).map(StaffUnderwritingController::ok);}

    @DisableIdempotency @PostMapping("/actions")
    @Operation(operationId="executeStaffUnderwritingAction",summary="Execute the exact prepared owner action once")
    @ApiResponses({
        @ApiResponse(responseCode="200",description="Original owner result: committed or refused",content=@Content(mediaType=MediaType.APPLICATION_JSON_VALUE,schema=@Schema(implementation=GovernedUnderwritingCommandResponse.class))),
        @ApiResponse(responseCode="503",description="Known commit with unavailable receipt, uncertain outcome or pre-dispatch platform failure. Keep the original action for recovery.",content=@Content(mediaType=MediaType.APPLICATION_JSON_VALUE,schema=@Schema(oneOf={GovernedUnderwritingCommandResponse.class,org.fireflyframework.web.error.models.ErrorResponse.class,org.fireflyframework.web.error.models.ProblemDetail.class})))})
    public Mono<ResponseEntity<GovernedUnderwritingCommandResponse>> execute(
        @Parameter(hidden=true) @RequestHeader(name=HttpHeaders.AUTHORIZATION,required=false) String authorization,
        @Valid @RequestBody GovernedUnderwritingCommandRequest command){return Mono.defer(()->service().execute(authorization,command))
            .map(body->ResponseEntity.status(body.getStatus()==GovernedUnderwritingCommandResponse.StatusEnum.COMMITTED||body.getStatus()==GovernedUnderwritingCommandResponse.StatusEnum.REFUSED?HttpStatus.OK:HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore()).body(body));}

    @DisableIdempotency @GetMapping("/actions/{actionId}")
    @Operation(operationId="recoverStaffUnderwritingAction",summary="Read the original action without repeating the mutation")
    @ApiResponse(responseCode="200",content=@Content(mediaType=MediaType.APPLICATION_JSON_VALUE,schema=@Schema(implementation=GovernedUnderwritingRecovery.class)))
    public Mono<ResponseEntity<GovernedUnderwritingRecovery>> recover(
        @Parameter(hidden=true) @RequestHeader(name=HttpHeaders.AUTHORIZATION,required=false) String authorization,
        @PathVariable UUID actionId,@RequestParam @Pattern(regexp="[0-9a-f]{64}") String intentDigest){
        return Mono.defer(()->service().recover(authorization,actionId,intentDigest)).map(StaffUnderwritingController::ok);
    }
    private GovernedStaffUnderwritingService service(){var value=services.getIfAvailable();if(value==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Trusted staff underwriting client is not configured");return value;}
    private static <T> ResponseEntity<T> ok(T body){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);}
}
