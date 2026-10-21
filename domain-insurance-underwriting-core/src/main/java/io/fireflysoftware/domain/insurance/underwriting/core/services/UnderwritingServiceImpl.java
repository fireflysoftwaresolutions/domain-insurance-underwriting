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

package io.fireflysoftware.domain.insurance.underwriting.core.services;

import io.fireflysoftware.core.insurance.underwriting.sdk.model.SubmissionDTO;
import io.fireflysoftware.domain.insurance.underwriting.core.commands.CreateSubmissionCommand;
import io.fireflysoftware.domain.insurance.underwriting.core.commands.GetSubmissionQuery;
import io.fireflysoftware.domain.insurance.underwriting.core.commands.UpdateSubmissionStatusCommand;
import io.fireflysoftware.domain.insurance.underwriting.core.commands.UpdateSubmissionCommand;
import io.fireflysoftware.domain.insurance.underwriting.core.commands.RemoveSubmissionCommand;
import io.fireflysoftware.domain.insurance.underwriting.core.lifecycle.SubmissionRecordLifecycle;
import io.fireflysoftware.domain.insurance.underwriting.core.constants.SubmissionSagaConstants;
import org.fireflyframework.cqrs.command.CommandBus;
import org.fireflyframework.cqrs.query.QueryBus;
import org.fireflyframework.orchestration.saga.engine.SagaEngine;
import org.fireflyframework.orchestration.saga.engine.SagaResult;
import org.fireflyframework.orchestration.saga.engine.StepInputs;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Runs the submission sagas and reads through the query bus.
 */
@Service
public class UnderwritingServiceImpl implements UnderwritingService {

    private final SagaEngine sagaEngine;
    private final QueryBus queryBus;
    private final CommandBus commandBus;

    public UnderwritingServiceImpl(SagaEngine sagaEngine, QueryBus queryBus, CommandBus commandBus) {
        this.sagaEngine = sagaEngine;
        this.queryBus = queryBus;
        this.commandBus = commandBus;
    }

    @Override
    public Mono<SubmissionDTO> create(CreateSubmissionCommand command) {
        StepInputs inputs = StepInputs.builder()
                .forStepId(SubmissionSagaConstants.STEP_REGISTER, command)
                .forStepId(SubmissionSagaConstants.STEP_RECORD_INITIAL_STATUS, command)
        .build();
        return sagaEngine.execute(SubmissionSagaConstants.SAGA_REGISTER, inputs)
                .flatMap(result -> outcome(result, SubmissionSagaConstants.STEP_REGISTER, SubmissionDTO.class));
    }

    @Override
    public Mono<SubmissionDTO> updateStatus(UpdateSubmissionStatusCommand command) {
        StepInputs inputs = StepInputs.builder()
                .forStepId(SubmissionSagaConstants.STEP_RETRIEVE, new GetSubmissionQuery(command.submissionId()))
                .forStepId(SubmissionSagaConstants.STEP_UPDATE_STATUS, command)
                .forStepId(SubmissionSagaConstants.STEP_RECORD_STATUS_HISTORY, command)
        .build();
        return sagaEngine.execute(SubmissionSagaConstants.SAGA_UPDATE_STATUS, inputs)
                .flatMap(result -> outcome(result, SubmissionSagaConstants.STEP_UPDATE_STATUS, SubmissionDTO.class));
    }

    @Override
    public Mono<SubmissionDTO> get(UUID submissionId) {
        return queryBus.query(new GetSubmissionQuery(submissionId));
    }


    @Override
    public Mono<SubmissionDTO> update(UpdateSubmissionCommand command) {
        StepInputs inputs = StepInputs.builder()
                .forStepId(SubmissionSagaConstants.STEP_RETRIEVE_FOR_UPDATE, new GetSubmissionQuery(command.submissionId()))
                .forStepId(SubmissionSagaConstants.STEP_UPDATE_RECORD, command)
                .build();
        return sagaEngine.execute(SubmissionSagaConstants.SAGA_UPDATE_RECORD, inputs)
                .flatMap(result -> outcome(result, SubmissionSagaConstants.STEP_UPDATE_RECORD, SubmissionDTO.class));
    }

    @Override
    public Mono<SubmissionDTO> withdraw(UUID submissionId, String statusReason) {
        return transition(submissionId, SubmissionDTO.SubmissionStatusEnum.WITHDRAWN, statusReason);
    }

    @Override
    public Mono<SubmissionDTO> decline(UUID submissionId, String statusReason) {
        return transition(submissionId, SubmissionDTO.SubmissionStatusEnum.DECLINED, statusReason);
    }

    private Mono<SubmissionDTO> transition(UUID submissionId, SubmissionDTO.SubmissionStatusEnum target, String statusReason) {
        return get(submissionId)
                .switchIfEmpty(Mono.error(new IllegalStateException("Submission was not found")))
                .flatMap(current -> {
                    if (!SubmissionRecordLifecycle.canTransition(current.getSubmissionStatus(), target)) {
                        String from = current.getSubmissionStatus() == null
                                ? "unset"
                                : current.getSubmissionStatus().getValue();
                        return Mono.<SubmissionDTO>error(new IllegalStateException(
                                "Submission " + submissionId + " cannot move from " + from + " to " + target.getValue()));
                    }
                    return updateStatus(new UpdateSubmissionStatusCommand(submissionId, target.getValue(), statusReason));
                });
    }

    @Override
    public Mono<Void> remove(UUID submissionId) {
        return get(submissionId)
                .switchIfEmpty(Mono.error(new IllegalStateException("Submission was not found")))
                .flatMap(current -> {
                    if (!SubmissionRecordLifecycle.canRemove(current.getSubmissionStatus())) {
                        return Mono.error(new IllegalStateException(
                                "Submission " + submissionId + " is " + current.getSubmissionStatus().getValue()
                                        + " and cannot be deleted; withdraw it so the submission history is kept"));
                    }
                    return commandBus.send(new RemoveSubmissionCommand(submissionId));
                });
    }

    private static <T> Mono<T> outcome(SagaResult result, String stepId, Class<T> type) {
        if (result.isFailed()) {
            Throwable error = result.error().orElseGet(() ->
                    new IllegalStateException("Saga " + result.sagaName() + " failed"));
            return Mono.error(error);
        }
        return Mono.justOrEmpty(result.resultOf(stepId, type))
                .switchIfEmpty(Mono.error(new IllegalStateException("Saga step " + stepId + " produced no result")));
    }
}
