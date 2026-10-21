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

package io.fireflysoftware.domain.insurance.underwriting.core.workflows;

import io.fireflysoftware.core.insurance.underwriting.sdk.model.SubmissionDTO;
import io.fireflysoftware.domain.insurance.underwriting.core.commands.ApplySubmissionStatusCommand;
import io.fireflysoftware.domain.insurance.underwriting.core.commands.GetSubmissionQuery;
import io.fireflysoftware.domain.insurance.underwriting.core.commands.UpdateSubmissionStatusCommand;

import io.fireflysoftware.core.insurance.underwriting.sdk.model.SubmissionStatusHistoryDTO;
import io.fireflysoftware.domain.insurance.underwriting.core.commands.RecordSubmissionStatusHistoryCommand;
import io.fireflysoftware.domain.insurance.underwriting.core.commands.RemoveSubmissionStatusHistoryCommand;
import java.time.LocalDateTime;

import org.fireflyframework.cqrs.command.CommandBus;
import org.fireflyframework.cqrs.query.QueryBus;
import org.fireflyframework.orchestration.core.context.ExecutionContext;
import org.fireflyframework.orchestration.saga.annotation.Saga;
import org.fireflyframework.orchestration.saga.annotation.SagaStep;
import org.fireflyframework.orchestration.saga.annotation.StepEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import static io.fireflysoftware.domain.insurance.underwriting.core.constants.SubmissionSagaConstants.COMPENSATE_REMOVE_STATUS_HISTORY;
import static io.fireflysoftware.domain.insurance.underwriting.core.constants.SubmissionSagaConstants.COMPENSATE_RESTORE;
import static io.fireflysoftware.domain.insurance.underwriting.core.constants.SubmissionSagaConstants.CTX_PREVIOUS_STATUS;
import static io.fireflysoftware.domain.insurance.underwriting.core.constants.SubmissionSagaConstants.CTX_RECORD;
import static io.fireflysoftware.domain.insurance.underwriting.core.constants.SubmissionSagaConstants.EVENT_RETRIEVED;
import static io.fireflysoftware.domain.insurance.underwriting.core.constants.SubmissionSagaConstants.EVENT_STATUS_HISTORY_RECORDED;
import static io.fireflysoftware.domain.insurance.underwriting.core.constants.SubmissionSagaConstants.EVENT_STATUS_UPDATED;
import static io.fireflysoftware.domain.insurance.underwriting.core.constants.SubmissionSagaConstants.SAGA_UPDATE_STATUS;
import static io.fireflysoftware.domain.insurance.underwriting.core.constants.SubmissionSagaConstants.STEP_RECORD_STATUS_HISTORY;
import static io.fireflysoftware.domain.insurance.underwriting.core.constants.SubmissionSagaConstants.STEP_RETRIEVE;
import static io.fireflysoftware.domain.insurance.underwriting.core.constants.SubmissionSagaConstants.STEP_UPDATE_STATUS;

/**
 * Changes a submission status by reading it and writing the new status through the core.
 */
@Saga(name = SAGA_UPDATE_STATUS)
@Service
public class UpdateSubmissionStatusSaga {

    private final CommandBus commandBus;
    private final QueryBus queryBus;

    @Autowired
    public UpdateSubmissionStatusSaga(CommandBus commandBus, QueryBus queryBus) {
        this.commandBus = commandBus;
        this.queryBus = queryBus;
    }

    @SagaStep(id = STEP_RETRIEVE)
    @StepEvent(type = EVENT_RETRIEVED)
    public Mono<SubmissionDTO> retrieveSubmission(GetSubmissionQuery query, ExecutionContext ctx) {
        return queryBus.query(query)
                .doOnNext(record -> ctx.putVariable(CTX_RECORD, record));
    }

    @SagaStep(id = STEP_UPDATE_STATUS, dependsOn = STEP_RETRIEVE, compensate = COMPENSATE_RESTORE)
    @StepEvent(type = EVENT_STATUS_UPDATED)
    public Mono<SubmissionDTO> updateSubmissionStatus(UpdateSubmissionStatusCommand command, ExecutionContext ctx) {
        SubmissionDTO current = ctx.getVariableAs(CTX_RECORD, SubmissionDTO.class);
        if (current == null || current.getSubmissionId() == null) {
            return Mono.error(new IllegalStateException("Submission was not found"));
        }
        if (current.getSubmissionStatus() == null) {
            return Mono.error(new IllegalStateException("Submission has no current status"));
        }
        if (command.status() == null || command.status().isBlank()) {
            return Mono.error(new IllegalArgumentException("Status is required"));
        }
        ctx.putVariable(CTX_PREVIOUS_STATUS, current.getSubmissionStatus().getValue());
        current.setSubmissionStatus(SubmissionDTO.SubmissionStatusEnum.fromValue(command.status()));
        return commandBus.send(new ApplySubmissionStatusCommand(current.getSubmissionId(), current))
                .doOnNext(updated -> ctx.putVariable(CTX_RECORD, updated));
    }

    public Mono<Void> restoreSubmissionStatus(SubmissionDTO updated, ExecutionContext ctx) {
        if (updated == null || updated.getSubmissionId() == null) {
            return Mono.empty();
        }
        String previous = ctx.getVariableAs(CTX_PREVIOUS_STATUS, String.class);
        if (previous == null || previous.isBlank()) {
            return Mono.empty();
        }
        updated.setSubmissionStatus(SubmissionDTO.SubmissionStatusEnum.fromValue(previous));
        return commandBus.send(new ApplySubmissionStatusCommand(updated.getSubmissionId(), updated)).then();
    }

    @SagaStep(id = STEP_RECORD_STATUS_HISTORY, dependsOn = STEP_UPDATE_STATUS, compensate = COMPENSATE_REMOVE_STATUS_HISTORY)
    @StepEvent(type = EVENT_STATUS_HISTORY_RECORDED)
    public Mono<SubmissionStatusHistoryDTO> recordStatusHistory(UpdateSubmissionStatusCommand command, ExecutionContext ctx) {
        SubmissionDTO current = ctx.getVariableAs(CTX_RECORD, SubmissionDTO.class);
        if (current == null || current.getSubmissionId() == null || current.getSubmissionStatus() == null) {
            return Mono.error(new IllegalStateException("Submission status was not updated"));
        }
        SubmissionStatusHistoryDTO history = new SubmissionStatusHistoryDTO();
        history.setSubmissionId(current.getSubmissionId());
        history.setSubmissionStatus(SubmissionStatusHistoryDTO.SubmissionStatusEnum.fromValue(current.getSubmissionStatus().getValue()));
        String reason = command.statusReason();
        history.setStatusReason(reason == null || reason.isBlank()
                ? "Status updated by the domain service"
                : reason);
        history.setChangedAt(LocalDateTime.now());
        return commandBus.send(new RecordSubmissionStatusHistoryCommand(history));
    }

    public Mono<Void> removeStatusHistory(SubmissionStatusHistoryDTO saved) {
        if (saved == null || saved.getSubmissionStatusHistoryId() == null || saved.getSubmissionId() == null) {
            return Mono.empty();
        }
        return commandBus.send(new RemoveSubmissionStatusHistoryCommand(saved.getSubmissionId(), saved.getSubmissionStatusHistoryId()));
    }

}
