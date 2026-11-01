/*
 * Copyright 2026 Firefly Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fireflysoftware.domain.insurance.underwriting.web.controllers;

import io.fireflysoftware.core.insurance.underwriting.sdk.model.*;
import io.fireflysoftware.domain.insurance.underwriting.core.services.quotes.GovernedQuoteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.fireflyframework.web.error.exceptions.ServiceUnavailableException;
import org.fireflyframework.web.error.models.ErrorResponse;
import org.fireflyframework.web.error.models.ProblemDetail;
import org.fireflyframework.web.idempotency.annotation.DisableIdempotency;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@RestController
@Validated
@RequestMapping("/api/v1/tenants/{tenantId}/submissions/{submissionId}/parties/{partyId}/products/{productId}/quote-offers")
@Tag(name="GovernedQuoteOffers",description="Exact owner offer, acceptance and recovery. Acceptance does not bind or issue a policy.")
@SecurityScheme(name="governedQuoteBearer",type=SecuritySchemeType.HTTP,scheme="bearer",bearerFormat="JWT")
@SecurityRequirement(name="governedQuoteBearer")
@ApiResponses({
    @ApiResponse(responseCode="400",description="Invalid request",content=@Content(mediaType=MediaType.APPLICATION_JSON_VALUE,schema=@Schema(implementation=ErrorResponse.class))),
    @ApiResponse(responseCode="401",description="Bearer authentication is required; an inbound filter may return an empty body",content=@Content(mediaType=MediaType.APPLICATION_JSON_VALUE,schema=@Schema(implementation=ErrorResponse.class))),
    @ApiResponse(responseCode="403",description="Current authorization is required; an inbound filter may return an empty body",content=@Content(mediaType=MediaType.APPLICATION_JSON_VALUE,schema=@Schema(implementation=ErrorResponse.class))),
    @ApiResponse(responseCode="502",description="Owner outcome is unconfirmed; this is not a refusal, absence or retry instruction",content=@Content(mediaType=MediaType.APPLICATION_PROBLEM_JSON_VALUE,schema=@Schema(implementation=ProblemDetail.class))),
    @ApiResponse(responseCode="503",description="Trusted owner client is absent before invocation",content=@Content(mediaType=MediaType.APPLICATION_JSON_VALUE,schema=@Schema(implementation=ErrorResponse.class)))})
public class GovernedQuoteController {
    private final ObjectProvider<GovernedQuoteService> services;
    public GovernedQuoteController(ObjectProvider<GovernedQuoteService> services) {this.services=Objects.requireNonNull(services);}

    @DisableIdempotency
    @PostMapping
    @Operation(operationId="offer",summary="Request an exact governed quote offer")
    @ApiResponse(responseCode="200",content=@Content(mediaType=MediaType.APPLICATION_JSON_VALUE,schema=@Schema(implementation=QuoteCommandResponse.class)))
    public Mono<ResponseEntity<QuoteCommandResponse>> offer(
            @Parameter(hidden=true) @RequestHeader(name=HttpHeaders.AUTHORIZATION,required=false) String authorization,
            @PathVariable UUID tenantId,@PathVariable UUID submissionId,@PathVariable UUID partyId,@PathVariable UUID productId,
            @Valid @RequestBody QuoteOfferRequest request) {
        return Mono.defer(()->{var service=owner();return invoked(()->service.offer(authorization,tenantId,submissionId,partyId,productId,request));});
    }

    @DisableIdempotency
    @PostMapping("/{offerId}/acceptances")
    @Operation(operationId="accept",summary="Accept the original offer version, control and terms")
    @ApiResponse(responseCode="200",content=@Content(mediaType=MediaType.APPLICATION_JSON_VALUE,schema=@Schema(implementation=QuoteCommandResponse.class)))
    public Mono<ResponseEntity<QuoteCommandResponse>> accept(
            @Parameter(hidden=true) @RequestHeader(name=HttpHeaders.AUTHORIZATION,required=false) String authorization,
            @PathVariable UUID tenantId,@PathVariable UUID submissionId,@PathVariable UUID partyId,@PathVariable UUID productId,@PathVariable UUID offerId,
            @Valid @RequestBody QuoteAcceptanceRequest request) {
        return Mono.defer(()->{var service=owner();return invoked(()->service.accept(authorization,tenantId,submissionId,partyId,productId,offerId,request));});
    }

    @DisableIdempotency
    @GetMapping("/{offerId}")
    @Operation(operationId="current",summary="Read the current authorized offer and acceptance receipt")
    @ApiResponse(responseCode="200",content=@Content(mediaType=MediaType.APPLICATION_JSON_VALUE,schema=@Schema(implementation=QuoteReadResponse.class)))
    public Mono<ResponseEntity<QuoteReadResponse>> current(
            @Parameter(hidden=true) @RequestHeader(name=HttpHeaders.AUTHORIZATION,required=false) String authorization,
            @PathVariable UUID tenantId,@PathVariable UUID submissionId,@PathVariable UUID partyId,@PathVariable UUID productId,@PathVariable UUID offerId) {
        return Mono.defer(()->{var service=owner();return invoked(()->service.current(authorization,tenantId,submissionId,partyId,productId,offerId));});
    }

    @DisableIdempotency
    @GetMapping
    @Operation(operationId="history",summary="Read owner-ordered authorized quote history")
    @ApiResponse(responseCode="200",content=@Content(mediaType=MediaType.APPLICATION_JSON_VALUE,schema=@Schema(implementation=QuoteHistoryResponse.class)))
    public Mono<ResponseEntity<QuoteHistoryResponse>> history(
            @Parameter(hidden=true) @RequestHeader(name=HttpHeaders.AUTHORIZATION,required=false) String authorization,
            @PathVariable UUID tenantId,@PathVariable UUID submissionId,@PathVariable UUID partyId,@PathVariable UUID productId) {
        return Mono.defer(()->{var service=owner();return invoked(()->service.history(authorization,tenantId,submissionId,partyId,productId));});
    }

    @DisableIdempotency
    @GetMapping("/actions/{actionId}/recovery")
    @Operation(operationId="recover",summary="Recover an existing action without another mutation")
    @ApiResponse(responseCode="200",content=@Content(mediaType=MediaType.APPLICATION_JSON_VALUE,schema=@Schema(implementation=QuoteRecoveryResponse.class)))
    public Mono<ResponseEntity<QuoteRecoveryResponse>> recover(
            @Parameter(hidden=true) @RequestHeader(name=HttpHeaders.AUTHORIZATION,required=false) String authorization,
            @PathVariable UUID tenantId,@PathVariable UUID submissionId,@PathVariable UUID partyId,@PathVariable UUID productId,@PathVariable UUID actionId,
            @RequestParam @Pattern(regexp="[0-9a-f]{64}") String expectedIntentDigest) {
        return Mono.defer(()->{var service=owner();return invoked(()->service.recover(authorization,tenantId,submissionId,partyId,productId,actionId,expectedIntentDigest));});
    }

    private static <T> Mono<ResponseEntity<T>> invoked(Supplier<Mono<T>> invocation) {
        return Mono.defer(invocation).switchIfEmpty(Mono.error(unconfirmed())).onErrorMap(error->{
            if(error instanceof ResponseStatusException response && Set.of(400,401,403,502).contains(response.getStatusCode().value()))return error;
            return unconfirmed();
        }).map(GovernedQuoteController::ok);
    }
    private static ResponseStatusException unconfirmed() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY,"QUOTE_OWNER_OUTCOME_UNCONFIRMED");
    }
    private GovernedQuoteService owner() {
        var service=services.getIfAvailable();
        if(service==null)throw new ServiceUnavailableException("QUOTE_OWNER_CLIENT_UNAVAILABLE","Trusted governed quote client required",
                "domain-insurance-underwriting",null,null);
        return service;
    }
    private static <T> ResponseEntity<T> ok(T body) {return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);}
}
