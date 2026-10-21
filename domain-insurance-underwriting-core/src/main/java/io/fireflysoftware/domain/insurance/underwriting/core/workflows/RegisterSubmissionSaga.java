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
import io.fireflysoftware.domain.insurance.underwriting.core.commands.CreateSubmissionCommand;
import io.fireflysoftware.domain.insurance.underwriting.core.commands.RemoveSubmissionCommand;

import io.fireflysoftware.core.insurance.underwriting.sdk.model.SubmissionStatusHistoryDTO;
import io.fireflysoftware.domain.insurance.underwriting.core.commands.RecordSubmissionStatusHistoryCommand;
import io.fireflysoftware.domain.insurance.underwriting.core.commands.RemoveSubmissionStatusHistoryCommand;
import java.time.LocalDateTime;

import org.fireflyframework.cqrs.command.CommandBus;
import org.fireflyframework.orchestration.core.context.ExecutionContext;
import org.fireflyframework.orchestration.saga.annotation.Saga;
import org.fireflyframework.orchestration.saga.annotation.SagaStep;
import org.fireflyframework.orchestration.saga.annotation.StepEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import static io.fireflysoftware.domain.insurance.underwriting.core.constants.SubmissionSagaConstants.COMPENSATE_REMOVE;
import static io.fireflysoftware.domain.insurance.underwriting.core.constants.SubmissionSagaConstants.COMPENSATE_REMOVE_INITIAL_STATUS;
import static io.fireflysoftware.domain.insurance.underwriting.core.constants.SubmissionSagaConstants.CTX_RECORD;
import static io.fireflysoftware.domain.insurance.underwriting.core.constants.SubmissionSagaConstants.EVENT_INITIAL_STATUS_RECORDED;
import static io.fireflysoftware.domain.insurance.underwriting.core.constants.SubmissionSagaConstants.EVENT_REGISTERED;
import static io.fireflysoftware.domain.insurance.underwriting.core.constants.SubmissionSagaConstants.SAGA_REGISTER;
import static io.fireflysoftware.domain.insurance.underwriting.core.constants.SubmissionSagaConstants.STEP_RECORD_INITIAL_STATUS;
import static io.fireflysoftware.domain.insurance.underwriting.core.constants.SubmissionSagaConstants.STEP_REGISTER;

/**
 * Creates a submission on {@code core-insurance-underwriting} and compensates by deleting it.
 */
@Saga(name = SAGA_REGISTER)
@Service
public class RegisterSubmissionSaga {

    private final CommandBus commandBus;

    @Autowired
    public RegisterSubmissionSaga(CommandBus commandBus) {
        this.commandBus = commandBus;
    }

    @SagaStep(id = STEP_REGISTER, compensate = COMPENSATE_REMOVE)
    @StepEvent(type = EVENT_REGISTERED)
    public Mono<SubmissionDTO> registerSubmission(CreateSubmissionCommand command, ExecutionContext ctx) {
        return commandBus.send(command)
                .doOnNext(created -> ctx.putVariable(CTX_RECORD, created));
    }

    public Mono<Void> removeSubmission(SubmissionDTO created) {
        if (created == null || created.getSubmissionId() == null) {
            return Mono.empty();
        }
        return commandBus.send(new RemoveSubmissionCommand(created.getSubmissionId()));
    }

    @SagaStep(id = STEP_RECORD_INITIAL_STATUS, dependsOn = STEP_REGISTER, compensate = COMPENSATE_REMOVE_INITIAL_STATUS)
    @StepEvent(type = EVENT_INITIAL_STATUS_RECORDED)
    public Mono<SubmissionStatusHistoryDTO> recordInitialStatus(CreateSubmissionCommand command, ExecutionContext ctx) {
        SubmissionDTO created = ctx.getVariableAs(CTX_RECORD, SubmissionDTO.class);
        if (created == null || created.getSubmissionId() == null) {
            return Mono.error(new IllegalStateException("Submission was not created"));
        }
        if (created.getSubmissionStatus() == null) {
            return Mono.error(new IllegalStateException("Submission status is required before history can be recorded"));
        }
        SubmissionStatusHistoryDTO history = new SubmissionStatusHistoryDTO();
        history.setSubmissionId(created.getSubmissionId());
        history.setSubmissionStatus(SubmissionStatusHistoryDTO.SubmissionStatusEnum.fromValue(created.getSubmissionStatus().getValue()));
        history.setStatusReason("Initial status recorded by the domain service");
        history.setChangedAt(LocalDateTime.now());
        return commandBus.send(new RecordSubmissionStatusHistoryCommand(history));
    }

    public Mono<Void> removeInitialStatus(SubmissionStatusHistoryDTO saved) {
        if (saved == null || saved.getSubmissionStatusHistoryId() == null || saved.getSubmissionId() == null) {
            return Mono.empty();
        }
        return commandBus.send(new RemoveSubmissionStatusHistoryCommand(saved.getSubmissionId(), saved.getSubmissionStatusHistoryId()));
    }

}
