/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.domain.insurance.underwriting.web.openapi;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.RestController;
import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("openapi-gen")
@SpringBootTest(classes=OpenApiGenApplication.class,webEnvironment=SpringBootTest.WebEnvironment.MOCK,properties={
    "spring.main.web-application-type=reactive","insurance.quotes.governed-client.enabled=false",
    "insurance.underwriting.staff-client.enabled=false","springdoc.api-docs.enabled=true","springdoc.api-docs.version=OPENAPI_3_0"})
class StaffUnderwritingOpenApiContractTest {
    @Autowired ApplicationContext context;
    private static final Set<String> VERBS=Set.of("get","post","put","patch","delete","head","options","trace");
    @Test void wholeOriginalProducerPreservesExistingOperationsAndPublishesTypedStaffRoutes() throws Exception {
        var client=WebTestClient.bindToApplicationContext(context).configureClient().responseTimeout(Duration.ofSeconds(30))
            .codecs(codecs->codecs.defaultCodecs().maxInMemorySize(4*1024*1024)).build();
        byte[] json=client.get().uri("/v3/api-docs").exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();
        byte[] yaml=client.get().uri("/v3/api-docs.yaml").exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();
        assertNotNull(json);assertNotNull(yaml);assertTrue(json.length<=4*1024*1024);assertTrue(yaml.length<=4*1024*1024);
        var actual=new ObjectMapper().readTree(json);var same=new ObjectMapper(new YAMLFactory()).readTree(yaml);assertEquals(actual,same);
        var baseline=new ObjectMapper(new YAMLFactory()).readTree(Files.readAllBytes(Path.of("../domain-insurance-underwriting-sdk/src/main/resources/openapi.yml")));
        var before=operations(baseline);var after=operations(actual);
        var staffKeys=Set.of("post /api/v1/staff/underwriting/intents","post /api/v1/staff/underwriting/actions","get /api/v1/staff/underwriting/actions/{actionId}");
        String quote="/api/v1/tenants/{tenantId}/submissions/{submissionId}/parties/{partyId}/products/{productId}/quote-offers";
        var quoteKeys=Set.of("post "+quote,"post "+quote+"/{offerId}/acceptances","get "+quote+"/{offerId}","get "+quote,"get "+quote+"/actions/{actionId}/recovery");
        var legacy=new TreeSet<>(before.keySet());legacy.removeAll(staffKeys);legacy.removeAll(quoteKeys);assertEquals(7,legacy.size());
        var expected=new TreeSet<>(legacy);expected.addAll(quoteKeys);assertEquals(12,expected.size());expected.addAll(staffKeys);assertEquals(15,after.size());assertEquals(expected,after.keySet());
        assertTrue(expected.containsAll(before.keySet()));
        byte[] originalTwelve=Files.readAllBytes(Path.of("src/test/resources/openapi/domain-quote-original.json"));
        assertEquals("078aa1bb145422a8a52151d490fa5f3826bd076f2913cac998e554a869a73efd",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(originalTwelve)));
        var priorProducer=new ObjectMapper().readTree(originalTwelve);assertEquals(12,operations(priorProducer).size());
        priorProducer.path("paths").fields().forEachRemaining(path->path.getValue().fields().forEachRemaining(method->{
            if(VERBS.contains(method.getKey()))assertOriginalOperation(method.getValue(),actual.path("paths").path(path.getKey()).path(method.getKey()),method.getKey()+" "+path.getKey());
        }));
        priorProducer.path("components").fields().forEachRemaining(section->section.getValue().fields().forEachRemaining(component->
            assertEquals(component.getValue(),actual.path("components").path(section.getKey()).path(component.getKey()),"Original producer "+section.getKey()+"/"+component.getKey())));
        assertEquals(after.size(),new HashSet<>(after.values()).size());
        baseline.path("paths").fields().forEachRemaining(path->path.getValue().fields().forEachRemaining(method->{
            if(VERBS.contains(method.getKey()))assertPublishedOperation(method.getValue(),actual.path("paths").path(path.getKey()).path(method.getKey()),method.getKey()+" "+path.getKey());
        }));
        baseline.path("components").fields().forEachRemaining(section->section.getValue().fields().forEachRemaining(component->
            assertEquals(component.getValue(),actual.path("components").path(section.getKey()).path(component.getKey()),section.getKey()+"/"+component.getKey())));
        var published=baseline.path("paths").path("/api/v1/submissions/{submissionId}/status").path("put");
        var current=actual.path("paths").path("/api/v1/submissions/{submissionId}/status").path("put");
        var changedSchema=current.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)changedSchema.path("parameters").get(1).path("schema")).put("type","integer");
        assertThrows(AssertionError.class,()->assertPublishedOperation(published,changedSchema,"changed header schema"));
        var changedDescription=current.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)changedDescription.path("parameters").get(1)).put("description","Unexpected idempotency semantics");
        assertThrows(AssertionError.class,()->assertPublishedOperation(published,changedDescription,"unrecognized header description"));
        var readRoute="get /api/v1/submissions/{submissionId}";
        var originalRead=priorProducer.path("paths").path("/api/v1/submissions/{submissionId}").path("get");
        var currentRead=actual.path("paths").path("/api/v1/submissions/{submissionId}").path("get");
        var unsignedRead=currentRead.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)unsignedRead).remove("security");
        assertThrows(AssertionError.class,()->assertOriginalOperation(originalRead,unsignedRead,readRoute));
        var wrongBearer=currentRead.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)wrongBearer).putArray("security").addObject().putArray("governedQuoteBearer");
        assertThrows(AssertionError.class,()->assertOriginalOperation(originalRead,wrongBearer,readRoute));
        assertEquals("bearer",actual.at("/components/securitySchemes/staffUnderwritingBearer/scheme").asText());
        for(var route:List.of(new String[]{"/api/v1/staff/underwriting/intents","post","prepareStaffUnderwritingIntent","GovernedUnderwritingPreparedIntent"},
            new String[]{"/api/v1/staff/underwriting/actions","post","executeStaffUnderwritingAction","GovernedUnderwritingCommandResponse"},
            new String[]{"/api/v1/staff/underwriting/actions/{actionId}","get","recoverStaffUnderwritingAction","GovernedUnderwritingRecovery"})){
            var operation=actual.path("paths").path(route[0]).path(route[1]);assertEquals(route[2],operation.path("operationId").asText());
            assertEquals(1,operation.path("security").size());assertTrue(operation.path("security").get(0).has("staffUnderwritingBearer"));
            for(var parameter:operation.path("parameters")){assertNotEquals("Authorization",parameter.path("name").asText());assertNotEquals("X-Idempotency-Key",parameter.path("name").asText());}
            assertEquals("#/components/schemas/"+route[3],operation.path("responses").path("200").path("content").path("application/json").path("schema").path("$ref").asText());
        }
        var alternatives=actual.path("paths").path("/api/v1/staff/underwriting/actions").path("post").path("responses").path("503").path("content").path("application/json").path("schema").path("oneOf");
        var names=new HashSet<String>();alternatives.forEach(value->names.add(value.path("$ref").asText()));
        assertEquals(Set.of("#/components/schemas/GovernedUnderwritingCommandResponse","#/components/schemas/ErrorResponse","#/components/schemas/ProblemDetail"),names);
        verifyRefs(actual,actual);
        var expectedControllers=new TreeSet<String>();var scanner=new org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new org.springframework.core.type.filter.AnnotationTypeFilter(RestController.class));
        scanner.findCandidateComponents("io.fireflysoftware.domain.insurance.underwriting.web.controllers").forEach(bean->expectedControllers.add(bean.getBeanClassName()));
        var observed=new TreeSet<String>();for(var bean:context.getBeansWithAnnotation(RestController.class).values()){
            var type=org.springframework.util.ClassUtils.getUserClass(bean);if(type.getName().startsWith("io.fireflysoftware.domain.insurance.underwriting.web.controllers.")){
                assertNotNull(AnnotationUtils.findAnnotation(type,RestController.class));observed.add(type.getName());
                assertEquals(OpenApiGenApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI().resolve("../classes/"),type.getProtectionDomain().getCodeSource().getLocation().toURI());
            }
        }
        assertFalse(expectedControllers.isEmpty());assertEquals(expectedControllers,observed);
        Files.write(Path.of("target/staff-underwriting-domain-openapi.json"),json);Files.write(Path.of("target/staff-underwriting-domain-openapi.yml"),yaml);
        if(Boolean.getBoolean("owned.insurance.domain-staff-sdk-boundary.enabled")){
            assertEquals("validated-hosted-manual-main-v1",System.getProperty("owned.insurance.domain-staff-sdk-boundary.hosted"));
            var root=Path.of(System.getProperty("owned.insurance.domain-staff-sdk-boundary.root")).normalize();assertTrue(root.isAbsolute());assertEquals(root,root.toRealPath());assertEquals(Path.of("..").toRealPath(),root);
            assertEquals(Path.of(System.getProperty("owned.insurance.domain-staff-sdk-boundary.workspace"),"qualification","underwriting-staff-source-chain","domain-insurance-underwriting").toRealPath(),root);
            var canonical=root.resolve("domain-insurance-underwriting-sdk/src/main/resources/openapi.yml");assertTrue(Files.isRegularFile(canonical,LinkOption.NOFOLLOW_LINKS));assertEquals(canonical,canonical.toRealPath());assertTrue(Files.size(canonical)<=4*1024*1024);
            byte[] preimage=Files.readAllBytes(canonical);assertEquals(System.getProperty("owned.insurance.domain-staff-sdk-boundary.preimage-sha256"),HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(preimage)));
            Files.write(Path.of("target/staff-underwriting-domain-sdk-preimage.yml"),preimage,StandardOpenOption.CREATE_NEW);
            Files.write(canonical,yaml,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING);assertArrayEquals(yaml,Files.readAllBytes(canonical));
        }
    }
    private static void assertPublishedOperation(JsonNode published,JsonNode actual,String route){
        var expected=published.deepCopy();
        // The old canonical predates Jafly's precise retry description; the pinned twelve-operation producer remains exact above.
        for(var parameter:expected.path("parameters")){
            if("header".equals(parameter.path("in").asText())&&"X-Idempotency-Key".equals(parameter.path("name").asText())
                &&"Unique key for idempotent requests. If provided, ensures that identical requests with the same key will only be processed once.".equals(parameter.path("description").asText())){
                ((com.fasterxml.jackson.databind.node.ObjectNode)parameter).put("description","Optional retry key for scoped POST, PUT, PATCH, and DELETE response reuse. Safe methods ignore it. Reuse requires configured trusted identity and namespace; changed input returns 409. Business effects require durable application idempotency.");
            }
        }
        assertOriginalOperation(expected,actual,route);
    }
    private static void assertOriginalOperation(JsonNode published,JsonNode actual,String route){
        var expected=published.deepCopy();
        if("get /api/v1/submissions/{submissionId}".equals(route)){
            var security=new ObjectMapper().createArrayNode();security.addObject().putArray("staffUnderwritingBearer");
            if(expected.has("security"))assertEquals(security,expected.path("security"));
            ((com.fasterxml.jackson.databind.node.ObjectNode)expected).set("security",security);
        }
        assertEquals(expected,actual,route);
    }
    private static Map<String,String> operations(JsonNode root){var result=new TreeMap<String,String>();root.path("paths").fields().forEachRemaining(path->path.getValue().fields().forEachRemaining(method->{if(VERBS.contains(method.getKey()))assertNull(result.put(method.getKey()+" "+path.getKey(),method.getValue().path("operationId").asText()));}));return result;}
    private static void verifyRefs(JsonNode root,JsonNode node){if(node.isObject()&&node.has("$ref")){String ref=node.path("$ref").asText();assertTrue(ref.startsWith("#/"));assertFalse(root.at(ref.substring(1)).isMissingNode(),ref);}if(node.isContainerNode())node.forEach(child->verifyRefs(root,child));}
}
