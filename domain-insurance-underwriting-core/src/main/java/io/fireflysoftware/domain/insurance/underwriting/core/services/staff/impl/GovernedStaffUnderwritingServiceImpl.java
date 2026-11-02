/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.domain.insurance.underwriting.core.services.staff.impl;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fireflysoftware.core.insurance.underwriting.sdk.invoker.ApiClient;
import io.fireflysoftware.core.insurance.underwriting.sdk.model.*;
import io.fireflysoftware.domain.insurance.underwriting.core.services.staff.GovernedStaffUnderwritingService;
import io.fireflysoftware.domain.insurance.underwriting.infra.staff.StaffUnderwritingClientFactory;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@Service
@ConditionalOnProperty(prefix="insurance.underwriting.staff-client",name="enabled",havingValue="true")
public final class GovernedStaffUnderwritingServiceImpl implements GovernedStaffUnderwritingService {
    private static final ObjectMapper JSON=ApiClient.createDefaultMapper(ApiClient.createDefaultDateFormat())
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> RECOVERY=Set.of("insurance:underwriting:actions:recover","insurance:underwriting:read");
    private final StaffUnderwritingClientFactory clients;
    public GovernedStaffUnderwritingServiceImpl(StaffUnderwritingClientFactory clients){this.clients=Objects.requireNonNull(clients);}

    @Override public Mono<GovernedUnderwritingPreparedIntent> prepare(String authorization,GovernedUnderwritingIntent supplied){
        final GovernedUnderwritingIntent input;
        try{input=capture(supplied);}catch(RuntimeException invalid){return Mono.error(badRequest());}
        return current(authorization,scopes(input)).flatMap(client->client.revalidate().then(Mono.defer(()->
            client.api().prepareGovernedUnderwritingIntentWithHttpInfo(input))).timeout(client.remaining())
            .map(reply->{var body=body(reply);if(!input.getActionId().equals(body.getActionId())||body.getOperation()==null
                ||!input.getOperation().getValue().equals(body.getOperation().getValue())||!digest(body.getIntentDigest()))throw unavailable();return body;})
            .flatMap(value->client.revalidate().thenReturn(value))).onErrorMap(GovernedStaffUnderwritingServiceImpl::safe);
    }

    @Override public Mono<GovernedUnderwritingCommandResponse> execute(String authorization,GovernedUnderwritingCommandRequest supplied){
        final GovernedUnderwritingCommandRequest input;
        try{if(supplied==null||!digest(supplied.getOriginalIntentDigest()))throw badRequest();input=new GovernedUnderwritingCommandRequest()
            .intent(capture(supplied.getIntent())).originalIntentDigest(supplied.getOriginalIntentDigest());}
        catch(RuntimeException invalid){return Mono.error(badRequest());}
        return current(authorization,scopes(input.getIntent())).flatMap(client->client.revalidate().then(Mono.defer(()->{
            var remaining=client.remaining();
            return Mono.defer(()->client.api().executeGovernedUnderwritingActionWithHttpInfo(input))
                .timeout(remaining).map(reply->commandReply(input,reply.getStatusCode().value(),reply.getBody()))
                .switchIfEmpty(Mono.fromSupplier(()->outcome(input,GovernedUnderwritingCommandResponse.StatusEnum.OUTCOME_UNKNOWN)))
                .onErrorResume(error->mutationFailure(input,error))
                .flatMap(value->value.getStatus()!=GovernedUnderwritingCommandResponse.StatusEnum.COMMITTED?Mono.just(value):
                    Mono.defer(client::revalidate).thenReturn(value)
                        .onErrorReturn(outcome(input,GovernedUnderwritingCommandResponse.StatusEnum.COMMITTED_RESPONSE_UNAVAILABLE)));
        }))).onErrorMap(GovernedStaffUnderwritingServiceImpl::safe);
    }

    @Override public Mono<GovernedUnderwritingRecovery> recover(String authorization,UUID actionId,String intentDigest){
        if(!id(actionId)||!digest(intentDigest))return Mono.error(badRequest());
        return current(authorization,RECOVERY).flatMap(client->client.revalidate().then(Mono.defer(()->
            client.api().recoverGovernedUnderwritingActionWithHttpInfo(actionId,intentDigest))).timeout(client.remaining())
            .map(reply->{var value=body(reply);if(value.getState()==null||!actionId.equals(value.getActionId())||!intentDigest.equals(value.getIntentDigest()))throw unavailable();
                boolean found=value.getState()==GovernedUnderwritingRecovery.StateEnum.FOUND;
                if(found!=(value.getReceipt()!=null)||found&&!receipt(value.getReceipt(),actionId,intentDigest))throw unavailable();return value;})
            .flatMap(value->client.revalidate().thenReturn(value))).onErrorMap(GovernedStaffUnderwritingServiceImpl::safe);
    }

    private Mono<StaffUnderwritingClientFactory.Client> current(String authorization,Set<String> scopes){
        return Mono.defer(()->clients.current(authorization,scopes)).switchIfEmpty(Mono.error(unavailable()));
    }
    private static Mono<GovernedUnderwritingCommandResponse> mutationFailure(GovernedUnderwritingCommandRequest input,Throwable error){
        if(error instanceof WebClientResponseException http){
            int status=http.getStatusCode().value();
            if(Set.of(400,401,403,404,409,422).contains(status))return Mono.error(new ResponseStatusException(http.getStatusCode(),"Current underwriting invocation refused"));
            byte[] raw=http.getResponseBodyAsByteArray();
            if(status==503&&raw.length>0&&raw.length<=65536){
                try{return Mono.just(commandReply(input,status,JSON.readValue(raw,GovernedUnderwritingCommandResponse.class)));}
                catch(Exception malformed){/* A malformed error body cannot establish the original operation's outcome. */}
            }
        }
        return Mono.just(outcome(input,GovernedUnderwritingCommandResponse.StatusEnum.OUTCOME_UNKNOWN));
    }
    private static GovernedUnderwritingCommandResponse commandReply(GovernedUnderwritingCommandRequest input,int status,GovernedUnderwritingCommandResponse value){
        UUID action=input.getIntent().getActionId();String digest=input.getOriginalIntentDigest();
        if(value==null||value.getStatus()==null||!action.equals(value.getActionId())||!digest.equals(value.getIntentDigest()))return outcome(input,GovernedUnderwritingCommandResponse.StatusEnum.OUTCOME_UNKNOWN);
        if(value.getStatus()==GovernedUnderwritingCommandResponse.StatusEnum.COMMITTED){
            var receipt=value.getReceipt();
            if(status!=200||!receipt(receipt,action,digest)||!input.getIntent().getOperation().getValue().equals(receipt.getOperation().getValue())
                ||input.getIntent().getReferralId()!=null&&!input.getIntent().getReferralId().equals(receipt.getReferralId()))return outcome(input,GovernedUnderwritingCommandResponse.StatusEnum.COMMITTED_RESPONSE_UNAVAILABLE);
            return value;
        }
        if(value.getReceipt()!=null||status!=200&&(status!=503||value.getStatus()==GovernedUnderwritingCommandResponse.StatusEnum.REFUSED))
            return outcome(input,GovernedUnderwritingCommandResponse.StatusEnum.OUTCOME_UNKNOWN);
        return value;
    }
    private static boolean receipt(GovernedUnderwritingReceipt value,UUID action,String digest){
        return value!=null&&action.equals(value.getActionId())&&digest.equals(value.getIntentDigest())&&value.getOperation()!=null
            &&id(value.getReferralId())&&value.getReferralVersion()!=null&&value.getReferralVersion()>=0&&digest(value.getReceiptDigest())
            &&(value.getOperation()==GovernedUnderwritingReceipt.OperationEnum.DECISION_RECORD?id(value.getDecisionId()):value.getDecisionId()==null);
    }
    private static GovernedUnderwritingCommandResponse outcome(GovernedUnderwritingCommandRequest input,GovernedUnderwritingCommandResponse.StatusEnum status){
        return new GovernedUnderwritingCommandResponse().status(status).actionId(input.getIntent().getActionId()).intentDigest(input.getOriginalIntentDigest());
    }
    private static GovernedUnderwritingIntent capture(GovernedUnderwritingIntent value){
        if(value==null||value.getOperation()==null||!id(value.getActionId())||!id(value.getSubmissionId())||!id(value.getRiskId())
            ||value.getExpectedReferralVersion()==null||value.getExpectedReferralVersion()<0)throw badRequest();
        return new GovernedUnderwritingIntent().operation(value.getOperation()).actionId(value.getActionId()).submissionId(value.getSubmissionId())
            .riskId(value.getRiskId()).referralId(value.getReferralId()).expectedReferralVersion(value.getExpectedReferralVersion())
            .expectedReceiptDigest(value.getExpectedReceiptDigest()).reason(value.getReason());
    }
    static Set<String> scopes(GovernedUnderwritingIntent value){return Set.of(switch(value.getOperation()){
        case REFERRAL_CREATE->"insurance:underwriting:referrals:create";
        case REFERRAL_APPROVE->"insurance:underwriting:referrals:approve";
        case DECISION_RECORD->"insurance:underwriting:decisions:record";
    });}
    private static <T>T body(ResponseEntity<T> value){if(value==null||value.getStatusCode().value()!=200||value.getBody()==null)throw unavailable();return value.getBody();}
    private static boolean id(UUID value){return value!=null&&!value.equals(new UUID(0,0));}
    private static boolean digest(String value){return value!=null&&value.matches("[0-9a-f]{64}");}
    private static Throwable safe(Throwable error){
        if(error instanceof ResponseStatusException status)return new ResponseStatusException(status.getStatusCode(),"Current underwriting operation unavailable");
        if(error instanceof WebClientResponseException http&&Set.of(400,401,403,404,409,422).contains(http.getStatusCode().value()))return new ResponseStatusException(http.getStatusCode(),"Current underwriting invocation refused");
        return unavailable();
    }
    private static ResponseStatusException badRequest(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"Original underwriting operation required");}
    private static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Original underwriting observation unavailable");}
}
