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
import io.fireflysoftware.domain.insurance.underwriting.core.commands.UpdateSubmissionStatusCommand;
import io.fireflysoftware.domain.insurance.underwriting.core.constants.SubmissionSagaConstants;
import org.fireflyframework.cqrs.command.CommandBus;
import org.fireflyframework.cqrs.query.QueryBus;
import org.fireflyframework.orchestration.core.context.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies a status change is written through the core and can be restored.
 */
@ExtendWith(MockitoExtension.class)
class UpdateSubmissionStatusSagaTest {

    @Mock
    private CommandBus commandBus;

    @Mock
    private QueryBus queryBus;

    private UpdateSubmissionStatusSaga saga;

    @BeforeEach
    void setUp() {
        saga = new UpdateSubmissionStatusSaga(commandBus, queryBus);
    }

    @Test
    void updateStatus_sendsTheRequestedStatusToTheCore() {
        UUID id = UUID.randomUUID();
        SubmissionDTO current = new SubmissionDTO(id);
        current.setSubmissionStatus(SubmissionDTO.SubmissionStatusEnum.RECEIVED);
        when(commandBus.send(any(ApplySubmissionStatusCommand.class))).thenReturn(Mono.just(current));

        ExecutionContext ctx = ExecutionContext.forSaga(SubmissionSagaConstants.SAGA_UPDATE_STATUS, "test");
        ctx.putVariable(SubmissionSagaConstants.CTX_RECORD, current);

        StepVerifier.create(saga.updateSubmissionStatus(new UpdateSubmissionStatusCommand(id, "IN_CLEARANCE", "Caller request"), ctx))
                .expectNext(current)
                .verifyComplete();

        ArgumentCaptor<ApplySubmissionStatusCommand> captor = ArgumentCaptor.forClass(ApplySubmissionStatusCommand.class);
        verify(commandBus).send(captor.capture());
        assertThat(captor.getValue().submissionId()).isEqualTo(id);
        assertThat(captor.getValue().record().getSubmissionStatus()).isEqualTo(SubmissionDTO.SubmissionStatusEnum.IN_CLEARANCE);
        assertThat(ctx.getVariableAs(SubmissionSagaConstants.CTX_PREVIOUS_STATUS, String.class)).isEqualTo("RECEIVED");
    }

    @Test
    void restore_writesThePreviousStatusBack() {
        UUID id = UUID.randomUUID();
        SubmissionDTO updated = new SubmissionDTO(id);
        updated.setSubmissionStatus(SubmissionDTO.SubmissionStatusEnum.IN_CLEARANCE);
        when(commandBus.send(any(ApplySubmissionStatusCommand.class))).thenReturn(Mono.just(updated));

        ExecutionContext ctx = ExecutionContext.forSaga(SubmissionSagaConstants.SAGA_UPDATE_STATUS, "test");
        ctx.putVariable(SubmissionSagaConstants.CTX_PREVIOUS_STATUS, "RECEIVED");

        StepVerifier.create(saga.restoreSubmissionStatus(updated, ctx))
                .verifyComplete();

        ArgumentCaptor<ApplySubmissionStatusCommand> captor = ArgumentCaptor.forClass(ApplySubmissionStatusCommand.class);
        verify(commandBus).send(captor.capture());
        assertThat(captor.getValue().record().getSubmissionStatus()).isEqualTo(SubmissionDTO.SubmissionStatusEnum.RECEIVED);
    }
}
