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

import io.fireflysoftware.core.insurance.underwriting.sdk.model.SubmissionDTO;
import io.fireflysoftware.domain.insurance.underwriting.core.commands.CreateSubmissionCommand;
import io.fireflysoftware.domain.insurance.underwriting.core.commands.UpdateSubmissionCommand;
import io.fireflysoftware.domain.insurance.underwriting.core.commands.UpdateSubmissionStatusCommand;
import io.fireflysoftware.domain.insurance.underwriting.core.services.UnderwritingService;
import io.fireflysoftware.domain.insurance.underwriting.interfaces.dtos.ReasonRequest;
import io.fireflysoftware.domain.insurance.underwriting.interfaces.dtos.StatusChangeRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Domain API for underwriting submissions.
 */
@RestController
@RequestMapping("/api/v1/submissions")
@Tag(name = "Underwriting", description = "Domain orchestration for underwriting submissions")
public class SubmissionsController {

    private final UnderwritingService service;

    public SubmissionsController(UnderwritingService service) {
        this.service = service;
    }

    @Operation(summary = "Create submission", description = "Runs the register saga against core-insurance-underwriting.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = SubmissionDTO.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content)
    })
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<SubmissionDTO>> create(@RequestBody SubmissionDTO body) {
        return service.create(new CreateSubmissionCommand(body))
                .map(created -> ResponseEntity.status(HttpStatus.CREATED).body(created));
    }

    @Operation(summary = "Get submission", description = "Reads one record from core-insurance-underwriting.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Found",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = SubmissionDTO.class))),
            @ApiResponse(responseCode = "404", description = "Not found", content = @Content)
    })
    @GetMapping(value = "/{submissionId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<SubmissionDTO>> get(
            @Parameter(description = "Core identifier", required = true) @PathVariable UUID submissionId) {
        return service.get(submissionId)
                .map(ResponseEntity::ok)
                .switchIfEmpty(Mono.just(ResponseEntity.notFound().build()));
    }

    @Operation(summary = "Update status", description = "Runs the status saga against core-insurance-underwriting.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = SubmissionDTO.class))),
            @ApiResponse(responseCode = "404", description = "Not found", content = @Content)
    })
    @PutMapping(value = "/{submissionId}/status", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<SubmissionDTO>> updateStatus(
            @Parameter(description = "Core identifier", required = true) @PathVariable UUID submissionId,
            @RequestBody StatusChangeRequest request) {
        return service.updateStatus(new UpdateSubmissionStatusCommand(submissionId, request.getStatus(), request.getStatusReason()))
                .map(ResponseEntity::ok)
                .switchIfEmpty(Mono.just(ResponseEntity.notFound().build()));
    }

    @Operation(summary = "Update submission", description = "Runs the update saga against core-insurance-underwriting. Status is left unchanged.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = SubmissionDTO.class))),
            @ApiResponse(responseCode = "404", description = "Not found", content = @Content)
    })
    @PutMapping(value = "/{submissionId}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<SubmissionDTO>> update(
            @Parameter(description = "Core identifier", required = true) @PathVariable UUID submissionId,
            @RequestBody SubmissionDTO body) {
        return service.update(new UpdateSubmissionCommand(submissionId, body))
                .map(ResponseEntity::ok)
                .switchIfEmpty(Mono.just(ResponseEntity.notFound().build()));
    }

    @Operation(summary = "Withdraw submission", description = "Withdraws a submission when the underwriting core allows WITHDRAWN.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = SubmissionDTO.class))),
            @ApiResponse(responseCode = "404", description = "Not found", content = @Content)
    })
    @PostMapping(value = "/{submissionId}/withdraw", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<SubmissionDTO>> withdraw(
            @Parameter(description = "Core identifier", required = true) @PathVariable UUID submissionId,
            @RequestBody ReasonRequest request) {
        return service.withdraw(submissionId, request.getStatusReason())
                .map(ResponseEntity::ok)
                .switchIfEmpty(Mono.just(ResponseEntity.notFound().build()));
    }

    @Operation(summary = "Decline submission", description = "Declines a submission when the underwriting core allows DECLINED.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = SubmissionDTO.class))),
            @ApiResponse(responseCode = "404", description = "Not found", content = @Content)
    })
    @PostMapping(value = "/{submissionId}/decline", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<SubmissionDTO>> decline(
            @Parameter(description = "Core identifier", required = true) @PathVariable UUID submissionId,
            @RequestBody ReasonRequest request) {
        return service.decline(submissionId, request.getStatusReason())
                .map(ResponseEntity::ok)
                .switchIfEmpty(Mono.just(ResponseEntity.notFound().build()));
    }

    @Operation(summary = "Remove submission", description = "Deletes the record through core-insurance-underwriting when that core allows removal.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Removed"),
            @ApiResponse(responseCode = "404", description = "Not found", content = @Content)
    })
    @DeleteMapping("/{submissionId}")
    public Mono<ResponseEntity<Void>> remove(
            @Parameter(description = "Core identifier", required = true) @PathVariable UUID submissionId) {
        return service.remove(submissionId)
                .thenReturn(ResponseEntity.noContent().build());
    }
}
