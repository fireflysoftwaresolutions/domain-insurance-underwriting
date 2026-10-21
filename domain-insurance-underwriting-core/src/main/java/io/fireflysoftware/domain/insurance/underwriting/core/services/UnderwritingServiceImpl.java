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
import io.fireflysoftware.domain.insurance.underwriting.core.constants.SubmissionSagaConstants;
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

    public UnderwritingServiceImpl(SagaEngine sagaEngine, QueryBus queryBus) {
        this.sagaEngine = sagaEngine;
        this.queryBus = queryBus;
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
