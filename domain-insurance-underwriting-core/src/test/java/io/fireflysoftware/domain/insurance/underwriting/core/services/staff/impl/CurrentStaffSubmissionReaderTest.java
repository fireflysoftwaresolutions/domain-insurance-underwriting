package io.fireflysoftware.domain.insurance.underwriting.core.services.staff.impl;

import io.fireflysoftware.domain.insurance.underwriting.infra.staff.StaffUnderwritingClientFactory;
import io.fireflysoftware.core.insurance.underwriting.sdk.api.GovernedUnderwritingApi;
import io.fireflysoftware.core.insurance.underwriting.sdk.invoker.ApiClient;
import io.fireflysoftware.core.insurance.underwriting.sdk.invoker.auth.HttpBearerAuth;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

/** Genuine retained full SDK over owned loopback HTTP; business owners/current delegation remain controlled. */
class CurrentStaffSubmissionReaderTest {
    final UUID tenant=UUID.randomUUID(),submission=UUID.randomUUID();
    final AtomicInteger checks=new AtomicInteger(),calls=new AtomicInteger();final AtomicReference<String> bearer=new AtomicReference<>();
    boolean foreignTenant,refuseResponse;int status=200;com.sun.net.httpserver.HttpServer server;
    @org.junit.jupiter.api.AfterEach void close(){if(server!=null)server.stop(0);}
    CurrentStaffSubmissionReaderImpl reader(){
        try{server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);}catch(java.io.IOException error){throw new AssertionError(error);}
        server.createContext("/api/v1/submissions/",request->{
            calls.incrementAndGet();assertEquals("GET",request.getRequestMethod());assertEquals("/api/v1/submissions/"+submission,request.getRequestURI().getPath());assertNull(request.getRequestURI().getQuery());
            bearer.set(request.getRequestHeaders().getFirst("Authorization"));assertNull(request.getRequestHeaders().getFirst("X-Idempotency-Key"));
            String body=status==200?"{\"submissionId\":\""+submission+"\",\"tenantId\":\""+(foreignTenant?UUID.randomUUID():tenant)+"\",\"submissionNumber\":\"SUB-CONTROLLED\"}":"";
            byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);request.getResponseHeaders().set("Content-Type","application/json");request.sendResponseHeaders(status,bytes.length==0?-1:bytes.length);if(bytes.length>0)request.getResponseBody().write(bytes);request.close();
        });server.start();
        var transport=ApiClient.buildWebClientBuilder().clientConnector(new org.springframework.http.client.reactive.JdkClientHttpConnector(java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(2)).build())).build();
        var client=new ApiClient(transport).setBasePath("http://127.0.0.1:"+server.getAddress().getPort());
        ((HttpBearerAuth)client.getAuthentication("governedQuoteBearer")).setBearerToken("controlled-generated-read-token");
        StaffUnderwritingClientFactory factory=(authorization,scopes)->{assertEquals("Bearer controlled-current-token",authorization);assertEquals(Set.of("insurance:underwriting:submissions:read"),scopes);
            return Mono.just(new StaffUnderwritingClientFactory.Client(){
                public GovernedUnderwritingApi api(){return new GovernedUnderwritingApi(client);}
                public Duration remaining(){return Duration.ofSeconds(5);}
                public Mono<Void> revalidate(){return Mono.defer(()->{int n=checks.incrementAndGet();return refuseResponse&&n>1?Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN)):Mono.empty();});}
            });
        };return new CurrentStaffSubmissionReaderImpl(factory,tenant);
    }
    @Test void exactReadUsesExistingFullSdkAndCurrentLeaseBeforeAndAfter(){var row=reader().read("Bearer controlled-current-token",submission).block();assertNotNull(row);assertEquals(submission,row.getSubmissionId());assertEquals(tenant,row.getTenantId());assertEquals(1,calls.get());assertEquals(2,checks.get());}
    @Test void originalProducerMustAttachTheNamedBearerToExistingGet(){reader().read("Bearer controlled-current-token",submission).block();assertTrue(bearer.get()!=null&&bearer.get().startsWith("Bearer "),"Original producer GET security must drive genuine SDK authentication");}
    @Test void foreignTenantBodyIsUnavailable(){foreignTenant=true;assertEquals(503,assertThrows(ResponseStatusException.class,()->reader().read("Bearer controlled-current-token",submission).block()).getStatusCode().value());}
    @Test void changedCurrentLeaseWithholdsOwnerRecord(){refuseResponse=true;assertEquals(403,assertThrows(ResponseStatusException.class,()->reader().read("Bearer controlled-current-token",submission).block()).getStatusCode().value());assertEquals(1,calls.get());}
    @Test void missingOwnerRecordRevalidatesBeforeReleasingNotFound(){status=404;assertEquals(404,assertThrows(ResponseStatusException.class,()->reader().read("Bearer controlled-current-token",submission).block()).getStatusCode().value());assertEquals(2,checks.get());}
}
