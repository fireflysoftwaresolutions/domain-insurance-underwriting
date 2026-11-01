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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.fireflysoftware.core.insurance.underwriting.sdk.model.*;
import io.fireflysoftware.domain.insurance.underwriting.core.services.quotes.GovernedQuoteService;
import java.time.Duration;
import java.util.*;
import org.fireflyframework.web.error.config.ErrorHandlingProperties;
import org.fireflyframework.web.error.converter.*;
import org.fireflyframework.web.error.handler.GlobalExceptionHandler;
import org.fireflyframework.web.error.service.ErrorResponseNegotiator;
import org.fireflyframework.web.idempotency.annotation.DisableIdempotency;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.config.DelegatingWebFluxConfiguration;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GovernedQuoteControllerTest {
    private static UUID id(long value){return new UUID(0,value);}
    private static final UUID TENANT=id(1),SUBMISSION=id(2),PARTY=id(3),PRODUCT=id(4),ACTION=id(5),OFFER=id(6);
    private static final String BASE="/api/v1/tenants/"+TENANT+"/submissions/"+SUBMISSION+"/parties/"+PARTY+"/products/"+PRODUCT+"/quote-offers";
    private static final String DIGEST="a".repeat(64);
    private static final class Fixture implements AutoCloseable {
        final AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext();
        final GovernedQuoteService owner=mock(GovernedQuoteService.class);
        final WebTestClient client;
        Fixture(boolean selected) {
            if(selected)context.registerBean(GovernedQuoteService.class,()->owner);
            context.registerBean(GovernedQuoteController.class,()->new GovernedQuoteController(context.getBeanProvider(GovernedQuoteService.class)));
            context.register(DelegatingWebFluxConfiguration.class);
            context.registerBean(org.springframework.validation.beanvalidation.MethodValidationPostProcessor.class,org.springframework.validation.beanvalidation.MethodValidationPostProcessor::new);
            var mapper=new ObjectMapper().registerModule(new JavaTimeModule());
            context.registerBean(GlobalExceptionHandler.class,()->new GlobalExceptionHandler(
                    new ExceptionConverterService(List.of(new ValidationExceptionConverter(),new WebFluxExceptionConverter())),Optional.empty(),
                    new ErrorHandlingProperties(),Optional.empty(),Optional.empty(),new MockEnvironment(),mapper,new ErrorResponseNegotiator(mapper),Optional.empty()));
            context.refresh();client=WebTestClient.bindToApplicationContext(context).configureClient().responseTimeout(Duration.ofSeconds(3)).build();
        }
        public void close(){context.close();}
    }
    private static Map<String,Object> offer() {return Map.of("actionId",ACTION.toString(),"coverageStart","2027-01-01","coverageEnd","2028-01-01",
            "expectedProductRevisionId",PRODUCT.toString(),"expectedProductDigest",DIGEST);}
    private static QuoteCommandResponse refused() {return new QuoteCommandResponse().status(QuoteCommandResponse.StatusEnum.REFUSED).actionId(ACTION).intentDigest(DIGEST);}

    @Test void missingSelectedClientUsesTheActualShared503WithoutCallingLegacyCrud() {
        try(var f=new Fixture(false)) {
            f.client.post().uri(BASE).header(HttpHeaders.AUTHORIZATION,"Bearer synthetic").bodyValue(offer()).exchange().expectStatus().isEqualTo(503)
                    .expectHeader().doesNotExist("Retry-After").expectBody().jsonPath("$.code").isEqualTo("QUOTE_OWNER_CLIENT_UNAVAILABLE");
            verifyNoInteractions(f.owner);
        }
    }

    @Test void typedOfferKeepsCallerPathActionOutcomeAndBearer() {
        try(var f=new Fixture(true)) {
            when(f.owner.offer(eq("Bearer synthetic"),eq(TENANT),eq(SUBMISSION),eq(PARTY),eq(PRODUCT),any())).thenReturn(Mono.just(refused()));
            f.client.post().uri(BASE).header(HttpHeaders.AUTHORIZATION,"Bearer synthetic").bodyValue(offer()).exchange().expectStatus().isOk()
                    .expectHeader().valueEquals("Cache-Control","no-store").expectBody().jsonPath("$.status").isEqualTo("REFUSED")
                    .jsonPath("$.actionId").isEqualTo(ACTION.toString()).jsonPath("$.intentDigest").isEqualTo(DIGEST);
            var captured=org.mockito.ArgumentCaptor.forClass(QuoteOfferRequest.class);
            verify(f.owner).offer(eq("Bearer synthetic"),eq(TENANT),eq(SUBMISSION),eq(PARTY),eq(PRODUCT),captured.capture());
            assertEquals(ACTION,captured.getValue().getActionId());verifyNoMoreInteractions(f.owner);
        }
    }

    @Test void typedAcceptanceRetainsOfferVersionControlTermsAndAction() {
        try(var f=new Fixture(true)) {
            when(f.owner.accept(anyString(),eq(TENANT),eq(SUBMISSION),eq(PARTY),eq(PRODUCT),eq(OFFER),any())).thenReturn(Mono.just(refused()));
            f.client.post().uri(BASE+"/"+OFFER+"/acceptances").header(HttpHeaders.AUTHORIZATION,"Bearer synthetic")
                    .bodyValue(Map.of("actionId",ACTION.toString(),"offerVersion",1,"expectedControlGeneration",0,"termsDigest",DIGEST))
                    .exchange().expectStatus().isOk().expectBody().jsonPath("$.actionId").isEqualTo(ACTION.toString());
            var captured=org.mockito.ArgumentCaptor.forClass(QuoteAcceptanceRequest.class);
            verify(f.owner).accept(eq("Bearer synthetic"),eq(TENANT),eq(SUBMISSION),eq(PARTY),eq(PRODUCT),eq(OFFER),captured.capture());
            assertEquals(1L,captured.getValue().getOfferVersion());assertEquals(0L,captured.getValue().getExpectedControlGeneration());
            assertEquals(DIGEST,captured.getValue().getTermsDigest());verifyNoMoreInteractions(f.owner);
        }
    }

    @Test void currentHistoryAndRecoveryUseTheirOwnObservationOperations() {
        try(var f=new Fixture(true)) {
            when(f.owner.current(anyString(),eq(TENANT),eq(SUBMISSION),eq(PARTY),eq(PRODUCT),eq(OFFER)))
                    .thenReturn(Mono.just(new QuoteReadResponse().state(QuoteReadResponse.StateEnum.NOT_OBSERVED_AT_THIS_READ)));
            when(f.owner.history(anyString(),eq(TENANT),eq(SUBMISSION),eq(PARTY),eq(PRODUCT)))
                    .thenReturn(Mono.just(new QuoteHistoryResponse().entries(List.of())));
            when(f.owner.recover(anyString(),eq(TENANT),eq(SUBMISSION),eq(PARTY),eq(PRODUCT),eq(ACTION),eq(DIGEST)))
                    .thenReturn(Mono.just(new QuoteRecoveryResponse().state(QuoteRecoveryResponse.StateEnum.NOT_OBSERVED_AT_THIS_READ).actionId(ACTION).intentDigest(DIGEST)));
            f.client.get().uri(BASE+"/"+OFFER).header(HttpHeaders.AUTHORIZATION,"Bearer synthetic").exchange().expectStatus().isOk()
                    .expectBody().jsonPath("$.state").isEqualTo("NOT_OBSERVED_AT_THIS_READ");
            f.client.get().uri(BASE).header(HttpHeaders.AUTHORIZATION,"Bearer synthetic").exchange().expectStatus().isOk().expectBody().jsonPath("$.entries").isArray();
            f.client.get().uri(BASE+"/actions/"+ACTION+"/recovery?expectedIntentDigest="+DIGEST).header(HttpHeaders.AUTHORIZATION,"Bearer synthetic")
                    .exchange().expectStatus().isOk().expectBody().jsonPath("$.actionId").isEqualTo(ACTION.toString());
            verify(f.owner).current("Bearer synthetic",TENANT,SUBMISSION,PARTY,PRODUCT,OFFER);
            verify(f.owner).history("Bearer synthetic",TENANT,SUBMISSION,PARTY,PRODUCT);
            verify(f.owner).recover("Bearer synthetic",TENANT,SUBMISSION,PARTY,PRODUCT,ACTION,DIGEST);verifyNoMoreInteractions(f.owner);
        }
    }

    @Test void unconfirmedOwnerErrorNeverBecomesA503OrCommandSuccess() {
        try(var f=new Fixture(true)) {
            when(f.owner.offer(anyString(),any(),any(),any(),any(),any())).thenReturn(Mono.error(new ResponseStatusException(HttpStatus.BAD_GATEWAY,"QUOTE_OWNER_OUTCOME_UNCONFIRMED")));
            f.client.post().uri(BASE).header(HttpHeaders.AUTHORIZATION,"Bearer synthetic").bodyValue(offer()).exchange().expectStatus().isEqualTo(502)
                    .expectHeader().doesNotExist("Retry-After").expectBody().jsonPath("$.actionId").doesNotExist();
        }
    }

    @Test void emptySelectedServiceCannotBecomeAnEmptySuccessOrAbsence() {
        try(var f=new Fixture(true)) {
            when(f.owner.offer(anyString(),any(),any(),any(),any(),any())).thenReturn(Mono.empty());
            when(f.owner.history(anyString(),any(),any(),any(),any())).thenReturn(Mono.empty());
            f.client.post().uri(BASE).header(HttpHeaders.AUTHORIZATION,"Bearer synthetic").bodyValue(offer()).exchange().expectStatus().isEqualTo(502);
            f.client.get().uri(BASE).header(HttpHeaders.AUTHORIZATION,"Bearer synthetic").exchange().expectStatus().isEqualTo(502);
        }
    }

    @Test void everyProtectedOperationOptOutOfTransportCaching() {
        var methods=Arrays.stream(GovernedQuoteController.class.getDeclaredMethods()).filter(method->Set.of("offer","accept","current","history","recover").contains(method.getName())).toList();
        assertEquals(5,methods.size());assertTrue(methods.stream().allMatch(method->method.isAnnotationPresent(DisableIdempotency.class)));
    }
}
