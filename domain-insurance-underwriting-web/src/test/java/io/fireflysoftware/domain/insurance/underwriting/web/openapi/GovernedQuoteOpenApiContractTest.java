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

package io.fireflysoftware.domain.insurance.underwriting.web.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("openapi-gen")
@SpringBootTest(classes=OpenApiGenApplication.class,webEnvironment=SpringBootTest.WebEnvironment.MOCK,properties={
        "spring.main.web-application-type=reactive","insurance.quotes.governed-client.enabled=false",
        "springdoc.api-docs.enabled=true","springdoc.api-docs.version=OPENAPI_3_0"})
class GovernedQuoteOpenApiContractTest {
    @Autowired ApplicationContext context;
    private static final String BASE="/api/v1/tenants/{tenantId}/submissions/{submissionId}/parties/{partyId}/products/{productId}/quote-offers";

    @Test void originalProducerDeclaresFiveTypedBearerOperationsWithoutTransportIdempotencyHeader() throws Exception {
        byte[] body=WebTestClient.bindToApplicationContext(context).configureClient().responseTimeout(Duration.ofSeconds(30))
                .codecs(codecs->codecs.defaultCodecs().maxInMemorySize(4*1024*1024)).build()
                .get().uri("/v3/api-docs").exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();
        assertNotNull(body);assertTrue(body.length<=4*1024*1024);
        Files.write(Path.of("target/governed-domain-openapi.json"),body,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);
        var document=new ObjectMapper().readTree(body);
        assertEquals("bearer",document.at("/components/securitySchemes/governedQuoteBearer/scheme").asText());
        for(var route:List.of(new String[]{BASE,"post","offer","QuoteCommandResponse"},new String[]{BASE+"/{offerId}/acceptances","post","accept","QuoteCommandResponse"},
                new String[]{BASE+"/{offerId}","get","current","QuoteReadResponse"},new String[]{BASE,"get","history","QuoteHistoryResponse"},
                new String[]{BASE+"/actions/{actionId}/recovery","get","recover","QuoteRecoveryResponse"})) {
            var operation=document.path("paths").path(route[0]).path(route[1]);assertEquals(route[2],operation.path("operationId").asText());
            assertTrue(operation.path("security").toString().contains("governedQuoteBearer"));
            for(var parameter:operation.path("parameters")) {
                assertNotEquals("X-Idempotency-Key",parameter.path("name").asText());
                assertNotEquals("Authorization",parameter.path("name").asText());
            }
            var content=operation.path("responses").path("200").path("content");assertTrue(content.has("application/json"));
            var schema=resolve(document,content.path("application/json").path("schema"));assertTrue(schema.path("properties").size()>0);
            if(route[3].equals("QuoteCommandResponse")) {
                var required=new HashSet<String>();schema.path("required").forEach(value->required.add(value.asText()));
                assertTrue(required.containsAll(Set.of("status","actionId","intentDigest")));
                var statuses=new HashSet<String>();resolve(document,schema.path("properties").path("status")).path("enum").forEach(value->statuses.add(value.asText()));
                assertEquals(Set.of("COMMITTED","COMMITTED_RESPONSE_UNAVAILABLE","OUTCOME_UNKNOWN","REFUSED"),statuses);
            }
        }
    }
    private static JsonNode resolve(JsonNode root,JsonNode value) {
        for(int step=0;value.has("$ref") && step<16;step++) {
            String ref=value.path("$ref").asText();assertTrue(ref.startsWith("#/components/schemas/"));value=root.at(ref.substring(1));
        }
        assertFalse(value.has("$ref"));return value;
    }
}
