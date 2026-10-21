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
import io.fireflysoftware.domain.insurance.underwriting.core.commands.UpdateSubmissionCommand;
import org.fireflyframework.cqrs.command.CommandBus;
import org.fireflyframework.cqrs.query.QueryBus;
import org.fireflyframework.orchestration.core.context.ExecutionContext;
import org.fireflyframework.orchestration.saga.annotation.Saga;
import org.fireflyframework.orchestration.saga.annotation.SagaStep;
import org.fireflyframework.orchestration.saga.annotation.StepEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import static io.fireflysoftware.domain.insurance.underwriting.core.constants.SubmissionSagaConstants.COMPENSATE_RESTORE_RECORD;
import static io.fireflysoftware.domain.insurance.underwriting.core.constants.SubmissionSagaConstants.CTX_PREVIOUS_RECORD;
import static io.fireflysoftware.domain.insurance.underwriting.core.constants.SubmissionSagaConstants.CTX_RECORD;
import static io.fireflysoftware.domain.insurance.underwriting.core.constants.SubmissionSagaConstants.EVENT_RECORD_UPDATED;
import static io.fireflysoftware.domain.insurance.underwriting.core.constants.SubmissionSagaConstants.EVENT_RETRIEVED;
import static io.fireflysoftware.domain.insurance.underwriting.core.constants.SubmissionSagaConstants.SAGA_UPDATE_RECORD;
import static io.fireflysoftware.domain.insurance.underwriting.core.constants.SubmissionSagaConstants.STEP_RETRIEVE_FOR_UPDATE;
import static io.fireflysoftware.domain.insurance.underwriting.core.constants.SubmissionSagaConstants.STEP_UPDATE_RECORD;

/**
 * Updates a submission on {@code core-insurance-underwriting} and compensates by writing the previous record back.
 * Status stays on the status saga.
 */
@Saga(name = SAGA_UPDATE_RECORD)
@Service
public class UpdateSubmissionSaga {

    private final CommandBus commandBus;
    private final QueryBus queryBus;

    @Autowired
    public UpdateSubmissionSaga(CommandBus commandBus, QueryBus queryBus) {
        this.commandBus = commandBus;
        this.queryBus = queryBus;
    }

    @SagaStep(id = STEP_RETRIEVE_FOR_UPDATE)
    @StepEvent(type = EVENT_RETRIEVED)
    public Mono<SubmissionDTO> retrieveSubmissionForUpdate(GetSubmissionQuery query, ExecutionContext ctx) {
        return queryBus.query(query)
                .doOnNext(record -> ctx.putVariable(CTX_RECORD, record));
    }

    @SagaStep(id = STEP_UPDATE_RECORD, dependsOn = STEP_RETRIEVE_FOR_UPDATE, compensate = COMPENSATE_RESTORE_RECORD)
    @StepEvent(type = EVENT_RECORD_UPDATED)
    public Mono<SubmissionDTO> updateSubmission(UpdateSubmissionCommand command, ExecutionContext ctx) {
        SubmissionDTO current = ctx.getVariableAs(CTX_RECORD, SubmissionDTO.class);
        if (current == null || current.getSubmissionId() == null) {
            return Mono.error(new IllegalStateException("Submission was not found"));
        }
        SubmissionDTO incoming = command.record();
        if (incoming == null) {
            return Mono.error(new IllegalArgumentException("Submission is required"));
        }
        if (current.getSubmissionStatus() != null) {
            incoming.setSubmissionStatus(current.getSubmissionStatus());
        }
        ctx.putVariable(CTX_PREVIOUS_RECORD, current);
        return commandBus.send(new ApplySubmissionStatusCommand(current.getSubmissionId(), incoming))
                .doOnNext(updated -> ctx.putVariable(CTX_RECORD, updated));
    }

    public Mono<Void> restoreSubmission(SubmissionDTO updated, ExecutionContext ctx) {
        SubmissionDTO previous = ctx.getVariableAs(CTX_PREVIOUS_RECORD, SubmissionDTO.class);
        if (previous == null || previous.getSubmissionId() == null) {
            return Mono.empty();
        }
        return commandBus.send(new ApplySubmissionStatusCommand(previous.getSubmissionId(), previous)).then();
    }
}
