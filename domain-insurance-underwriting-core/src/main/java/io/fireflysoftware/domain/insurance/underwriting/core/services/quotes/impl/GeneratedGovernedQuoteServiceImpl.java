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

package io.fireflysoftware.domain.insurance.underwriting.core.services.quotes.impl;

import io.fireflysoftware.core.insurance.underwriting.sdk.model.*;
import io.fireflysoftware.domain.insurance.underwriting.core.services.quotes.GovernedQuoteService;
import io.fireflysoftware.domain.insurance.underwriting.infra.GovernedQuoteClientFactory;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@Service
@ConditionalOnProperty(prefix="insurance.quotes.governed-client",name="enabled",havingValue="true")
public final class GeneratedGovernedQuoteServiceImpl implements GovernedQuoteService {
    private final GovernedQuoteClientFactory clients;
    public GeneratedGovernedQuoteServiceImpl(GovernedQuoteClientFactory clients) {this.clients=Objects.requireNonNull(clients);}

    @Override
    public Mono<QuoteCommandResponse> offer(String authorization,UUID tenantId,UUID submissionId,UUID partyId,UUID productId,QuoteOfferRequest request) {
        return Mono.defer(()->{
            if(request==null)return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,"QUOTE_INPUT_REQUIRED"));
            var captured=new QuoteOfferRequest().actionId(request.getActionId()).coverageStart(request.getCoverageStart())
                    .coverageEnd(request.getCoverageEnd()).expectedProductRevisionId(request.getExpectedProductRevisionId())
                    .expectedProductDigest(request.getExpectedProductDigest());
            UUID actionId=captured.getActionId();
            return clients.invoke(authorization,java.util.Set.of("insurance:quotes:offer"),api->body(api.offerWithHttpInfo(tenantId,submissionId,partyId,productId,captured))
                    .map(value->command(value,actionId,new Scope(tenantId,submissionId,partyId,productId))));
        }).onErrorMap(GeneratedGovernedQuoteServiceImpl::safeFailure);
    }

    @Override
    public Mono<QuoteCommandResponse> accept(String authorization,UUID tenantId,UUID submissionId,UUID partyId,UUID productId,UUID offerId,QuoteAcceptanceRequest request) {
        return Mono.defer(()->{
            if(request==null)return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,"QUOTE_INPUT_REQUIRED"));
            var captured=new QuoteAcceptanceRequest().actionId(request.getActionId()).offerVersion(request.getOfferVersion())
                    .expectedControlGeneration(request.getExpectedControlGeneration()).termsDigest(request.getTermsDigest());
            UUID actionId=captured.getActionId();
            return clients.invoke(authorization,java.util.Set.of("insurance:quotes:accept"),api->body(api.acceptWithHttpInfo(tenantId,submissionId,partyId,productId,offerId,captured))
                    .map(value->command(value,actionId,new Scope(tenantId,submissionId,partyId,productId))));
        }).onErrorMap(GeneratedGovernedQuoteServiceImpl::safeFailure);
    }

    @Override
    public Mono<QuoteReadResponse> current(String authorization,UUID tenantId,UUID submissionId,UUID partyId,UUID productId,UUID offerId) {
        return Mono.defer(()->clients.invoke(authorization,java.util.Set.of("insurance:quotes:read"),api->
                body(api.currentWithHttpInfo(tenantId,submissionId,partyId,productId,offerId))
                        .map(value->currentReply(value,new Scope(tenantId,submissionId,partyId,productId),offerId))))
                .onErrorMap(GeneratedGovernedQuoteServiceImpl::safeFailure);
    }

    @Override
    public Mono<QuoteHistoryResponse> history(String authorization,UUID tenantId,UUID submissionId,UUID partyId,UUID productId) {
        return Mono.defer(()->clients.invoke(authorization,java.util.Set.of("insurance:quotes:history:read"),api->
                body(api.historyWithHttpInfo(tenantId,submissionId,partyId,productId))
                        .map(value->historyReply(value,new Scope(tenantId,submissionId,partyId,productId)))))
                .onErrorMap(GeneratedGovernedQuoteServiceImpl::safeFailure);
    }

    @Override
    public Mono<QuoteRecoveryResponse> recover(String authorization,UUID tenantId,UUID submissionId,UUID partyId,UUID productId,UUID actionId,String expectedIntentDigest) {
        return Mono.defer(()->clients.invoke(authorization,java.util.Set.of("insurance:quotes:recover","insurance:quotes:read"),api->
                body(api.recoverWithHttpInfo(tenantId,submissionId,partyId,productId,actionId,expectedIntentDigest))
                        .map(value->recoveryReply(value,new Scope(tenantId,submissionId,partyId,productId),actionId,expectedIntentDigest))))
                .onErrorMap(GeneratedGovernedQuoteServiceImpl::safeFailure);
    }

    private static QuoteReadResponse currentReply(QuoteReadResponse value,Scope scope,UUID offerId) {
        if(value.getState()==null || ("FOUND".equals(value.getState().toString()))!=(value.getEntry()!=null)
                || value.getEntry()!=null && (value.getEntry().getOffer()==null || !scope.matches(value.getEntry().getOffer().getScope())
                || !Objects.equals(offerId,value.getEntry().getOffer().getOfferId())))throw unavailable();return value;
    }
    private static QuoteHistoryResponse historyReply(QuoteHistoryResponse value,Scope scope) {
        if(value.getEntries()==null || value.getEntries().stream().anyMatch(entry->entry==null || entry.getOffer()==null
                || !scope.matches(entry.getOffer().getScope())))throw unavailable();return value;
    }
    private static QuoteRecoveryResponse recoveryReply(QuoteRecoveryResponse value,Scope scope,UUID actionId,String digest) {
        if(value.getState()==null || !Objects.equals(actionId,value.getActionId()) || !Objects.equals(digest,value.getIntentDigest())
                || ("FOUND".equals(value.getState().toString()))!=(value.getResult()!=null)
                || value.getResult()!=null && !matchesResult(value.getResult(),actionId,digest,scope))throw unavailable();return value;
    }

    private static QuoteCommandResponse command(QuoteCommandResponse value,UUID actionId,Scope scope) {
        if(value.getStatus()==null || actionId==null || !actionId.equals(value.getActionId()) || value.getIntentDigest()==null
                || !value.getIntentDigest().matches("[0-9a-f]{64}")
                || ("COMMITTED".equals(value.getStatus().toString()))!=(value.getResult()!=null)
                || value.getResult()!=null && !matchesResult(value.getResult(),actionId,value.getIntentDigest(),scope))throw unavailable();
        return value;
    }

    private static boolean matchesResult(QuoteResultEnvelope result,UUID actionId,String digest,Scope scope) {
        var action=result.getAction();
        return action!=null && scope.matches(action.getScope()) && Objects.equals(actionId,action.getActionId())
                && Objects.equals(digest,action.getIntentDigest()) && result.getResultId()!=null && result.getOfferVersionId()!=null
                && result.getRecordedAt()!=null;
    }

    private record Scope(UUID tenantId,UUID submissionId,UUID partyId,UUID productId) {
        boolean matches(QuoteOfferScope value) {
            return value!=null && Objects.equals(tenantId,value.getTenantId()) && Objects.equals(submissionId,value.getSubmissionId())
                    && Objects.equals(partyId,value.getPartyId()) && Objects.equals(productId,value.getProductId());
        }
    }

    private static <T> Mono<T> body(Mono<ResponseEntity<T>> invocation) {
        return invocation.flatMap(reply->reply.getStatusCode().value()==200 && reply.getBody()!=null
                ? Mono.just(reply.getBody()) : Mono.error(unavailable())).switchIfEmpty(Mono.error(unavailable()));
    }

    private static Throwable safeFailure(Throwable error) {
        if(error instanceof ResponseStatusException response && java.util.Set.of(400,401,403,502).contains(response.getStatusCode().value()))return error;
        if(error instanceof WebClientResponseException response && (response.getStatusCode().value()==400
                || response.getStatusCode().value()==401 || response.getStatusCode().value()==403))
            return new ResponseStatusException(response.getStatusCode(),"QUOTE_OWNER_REQUEST_REJECTED");
        return unavailable();
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY,"QUOTE_OWNER_OUTCOME_UNCONFIRMED");
    }
}
