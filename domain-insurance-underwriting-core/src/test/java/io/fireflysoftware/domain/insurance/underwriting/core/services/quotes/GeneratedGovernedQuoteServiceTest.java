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

package io.fireflysoftware.domain.insurance.underwriting.core.services.quotes;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fireflysoftware.core.insurance.underwriting.sdk.invoker.ApiClient;
import io.fireflysoftware.core.insurance.underwriting.sdk.model.QuoteOfferRequest;
import io.fireflysoftware.domain.insurance.underwriting.core.services.quotes.impl.GeneratedGovernedQuoteServiceImpl;
import io.fireflysoftware.domain.insurance.underwriting.infra.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.*;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.client.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import static org.junit.jupiter.api.Assertions.*;

class GeneratedGovernedQuoteServiceTest {
    private static final UUID TENANT=id(1),SUBMISSION=id(2),PARTY=id(3),PRODUCT=id(4),ACTION=id(5);
    private static final String DIGEST="a".repeat(64);
    private static final Duration LIMIT=Duration.ofSeconds(5);
    private static final ObjectMapper MAPPER=ApiClient.createDefaultMapper(null);
    private static final ExchangeStrategies STRATEGIES=ExchangeStrategies.builder().codecs(codecs->{
        codecs.defaultCodecs().jackson2JsonEncoder(new org.springframework.http.codec.json.Jackson2JsonEncoder(MAPPER,MediaType.APPLICATION_JSON));
        codecs.defaultCodecs().jackson2JsonDecoder(new org.springframework.http.codec.json.Jackson2JsonDecoder(MAPPER,MediaType.APPLICATION_JSON));
    }).build();
    private static UUID id(long value){return new UUID(0,value);}
    private static GeneratedGovernedQuoteServiceImpl service(ExchangeFunction exchange) {
        var transport=ApiClient.buildWebClientBuilder().exchangeFunction(exchange).build();
        GovernedQuoteClientFactory clients=new GovernedQuoteClientFactory() {
            @Override public <T> Mono<T> invoke(String authorization,Set<String> requiredScopes,
                    java.util.function.Function<io.fireflysoftware.core.insurance.underwriting.sdk.api.GovernedQuoteOffersApi,Mono<T>> operation) {
                return Mono.defer(()->{
                    var client=new ApiClient(transport).setBasePath("https://configured-owner.invalid");client.setBearerToken(authorization.substring(7));
                    return operation.apply(new io.fireflysoftware.core.insurance.underwriting.sdk.api.GovernedQuoteOffersApi(client));
                });
            }
        };
        return new GeneratedGovernedQuoteServiceImpl(clients);
    }
    private static QuoteOfferRequest request() {
        return new QuoteOfferRequest().actionId(ACTION).coverageStart(LocalDate.of(2027,1,1)).coverageEnd(LocalDate.of(2028,1,1))
                .expectedProductRevisionId(PRODUCT).expectedProductDigest(DIGEST);
    }
    private static ClientResponse response(String json){return ClientResponse.create(HttpStatus.OK,STRATEGIES).header("Content-Type","application/json").body(json).build();}
    private static String result(UUID action,UUID tenant) throws Exception {
        return MAPPER.writeValueAsString(Map.of("action",Map.of("scope",Map.of("tenantId",tenant,"submissionId",SUBMISSION,"partyId",PARTY,"productId",PRODUCT),
                "ownerIssuer","https://synthetic-issuer.invalid","ownerSubject","synthetic-subject","operation","OFFER","offerId",id(6),
                "offerVersion",1,"expectedControlGeneration",0,"actionId",action,"intentDigest",DIGEST),"resultId",id(7),
                "offerVersionId",id(8),"recordedAt","2026-10-31T12:00:00Z","resultingControlGeneration",0));
    }
    private static String command(String status,UUID action,String result) {
        return "{\"status\":\""+status+"\",\"actionId\":\""+action+"\",\"intentDigest\":\""+DIGEST+"\""+(result==null?"":",\"result\":"+result)+"}";
    }

    @ParameterizedTest @ValueSource(strings={"COMMITTED","COMMITTED_RESPONSE_UNAVAILABLE","OUTCOME_UNKNOWN","REFUSED"})
    void allOwnerOutcomesKeepOriginalIdentityAndNeverRetry(String status) throws Exception {
        var calls=new AtomicInteger();String body=command(status,ACTION,status.equals("COMMITTED")?result(ACTION,TENANT):null);
        var owner=service(request->{calls.incrementAndGet();return Mono.just(response(body));});
        var value=owner.offer("Bearer synthetic-caller",TENANT,SUBMISSION,PARTY,PRODUCT,request()).block(LIMIT);
        assertNotNull(value);assertEquals(status,value.getStatus().toString());assertEquals(ACTION,value.getActionId());assertEquals(DIGEST,value.getIntentDigest());
        assertEquals(status.equals("COMMITTED"),value.getResult()!=null);assertEquals(1,calls.get());
    }

    @Test void callerMutationAfterSubscriptionCannotChangeCapturedRequestAndEachSubscriptionRecaptures() throws Exception {
        List<Map<String,String>> captured=new ArrayList<>();var calls=new AtomicInteger();Sinks.Empty<Void> release=Sinks.empty();
        var owner=service(request->{
            int number=calls.incrementAndGet();var target=new MockClientHttpRequest(request.method(),request.url());
            Mono<Void> ready=number==1?release.asMono():Mono.empty();
            return ready.then(Mono.defer(()->request.writeTo(target,STRATEGIES))).then(Mono.defer(target::getBodyAsString)).map(json->{
                try {
                    var node=MAPPER.readTree(json);var values=new LinkedHashMap<String,String>();
                    node.fields().forEachRemaining(field->values.put(field.getKey(),field.getValue().asText()));captured.add(values);
                    return response(command("REFUSED",UUID.fromString(node.path("actionId").asText()),null));
                } catch(Exception invalid){throw new IllegalStateException(invalid);}
            });
        });
        var input=request();var operation=owner.offer("Bearer synthetic-caller",TENANT,SUBMISSION,PARTY,PRODUCT,input);
        var first=operation.toFuture();assertEquals(1,calls.get());
        input.actionId(id(100)).coverageStart(LocalDate.of(2029,1,1)).coverageEnd(LocalDate.of(2030,1,1))
                .expectedProductRevisionId(id(101)).expectedProductDigest("b".repeat(64));
        release.tryEmitEmpty();assertEquals(ACTION,first.get(5,java.util.concurrent.TimeUnit.SECONDS).getActionId());
        assertEquals(Map.of("actionId",ACTION.toString(),"coverageStart","2027-01-01","coverageEnd","2028-01-01",
                "expectedProductRevisionId",PRODUCT.toString(),"expectedProductDigest",DIGEST),captured.get(0));
        assertEquals(id(100),operation.block(LIMIT).getActionId());assertEquals(id(100).toString(),captured.get(1).get("actionId"));
        assertEquals("b".repeat(64),captured.get(1).get("expectedProductDigest"));assertEquals(2,calls.get());
    }

    @Test void twoOverlappingOwnerCallsCannotShareBearerAuthentication() {
        Map<String,Integer> headers=new ConcurrentHashMap<>();var calls=new AtomicInteger();Sinks.Empty<Void> both=Sinks.empty();
        var owner=service(request->Mono.defer(()->{
            headers.merge(request.headers().getFirst(HttpHeaders.AUTHORIZATION),1,Integer::sum);if(calls.incrementAndGet()==2)both.tryEmitEmpty();
            return both.asMono().thenReturn(response("{\"entries\":[]}"));
        }));
        Mono.when(owner.history("Bearer synthetic-one",TENANT,SUBMISSION,PARTY,PRODUCT),
                owner.history("Bearer synthetic-two",TENANT,SUBMISSION,PARTY,PRODUCT)).block(LIMIT);
        assertEquals(Map.of("Bearer synthetic-one",1,"Bearer synthetic-two",1),headers);
    }

    @Test void emptyWrongActionOrForeignProtectedResultNeverBecomesSuccessOrRefusal() throws Exception {
        List<ClientResponse> replies=List.of(ClientResponse.create(HttpStatus.OK).build(),response(command("REFUSED",id(99),null)),
                response(command("COMMITTED",ACTION,null)),response(command("COMMITTED",ACTION,result(ACTION,id(99)))));
        for(var reply:replies) {
            var owner=service(request->Mono.just(reply));
            var failure=assertThrows(ResponseStatusException.class,()->owner.offer("Bearer synthetic",TENANT,SUBMISSION,PARTY,PRODUCT,request()).block(LIMIT));
            assertEquals(502,failure.getStatusCode().value());assertNull(failure.getCause());
        }
    }

    @Test void unknownTransportAndOwner503DoNotBecomeRetryable503OrLeakCause() {
        for(var exchange:List.<ExchangeFunction>of(request->Mono.error(new IllegalStateException("SYNTHETIC-PRIVATE-DETAIL")),
                request->Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).body("SYNTHETIC-PRIVATE-DETAIL").build()))) {
            var failure=assertThrows(ResponseStatusException.class,()->service(exchange).offer("Bearer synthetic",TENANT,SUBMISSION,PARTY,PRODUCT,request()).block(LIMIT));
            assertEquals(502,failure.getStatusCode().value());assertFalse(failure.toString().contains("SYNTHETIC-PRIVATE-DETAIL"));assertNull(failure.getCause());
        }
    }

    @Test void readErrorCannotFabricateEmptyHistoryOrNotObserved() {
        var owner=service(request->Mono.error(new IllegalStateException("SYNTHETIC")));
        assertThrows(ResponseStatusException.class,()->owner.current("Bearer synthetic",TENANT,SUBMISSION,PARTY,PRODUCT,id(6)).block(LIMIT));
        assertThrows(ResponseStatusException.class,()->owner.history("Bearer synthetic",TENANT,SUBMISSION,PARTY,PRODUCT).block(LIMIT));
    }

    @Test void cancellingObservationCancelsTheActualGeneratedClientSubscription() {
        var cancelled=new java.util.concurrent.atomic.AtomicBoolean();
        var owner=service(request->Mono.<ClientResponse>never().doOnCancel(()->cancelled.set(true)));
        var subscription=owner.current("Bearer synthetic",TENANT,SUBMISSION,PARTY,PRODUCT,id(6)).subscribe();
        subscription.dispose();assertTrue(cancelled.get());
    }

    @Test void recoveryRetainsTheOriginalActionDigestAndDoesNotWrite() {
        var calls=new AtomicInteger();
        var owner=service(request->{
            calls.incrementAndGet();assertEquals(HttpMethod.GET,request.method());assertTrue(request.url().getPath().endsWith("/actions/"+ACTION+"/recovery"));
            assertEquals("expectedIntentDigest="+DIGEST,request.url().getRawQuery());
            return Mono.just(response("{\"state\":\"NOT_OBSERVED_AT_THIS_READ\",\"actionId\":\""+ACTION+"\",\"intentDigest\":\""+DIGEST+"\"}"));
        });
        var value=owner.recover("Bearer synthetic",TENANT,SUBMISSION,PARTY,PRODUCT,ACTION,DIGEST).block(LIMIT);
        assertNotNull(value);assertEquals("NOT_OBSERVED_AT_THIS_READ",value.getState().toString());assertEquals(ACTION,value.getActionId());assertEquals(1,calls.get());
    }
}
