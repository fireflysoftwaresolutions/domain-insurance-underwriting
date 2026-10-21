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
import io.fireflysoftware.domain.insurance.underwriting.core.commands.UpdateSubmissionCommand;
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
 * Verifies a field update keeps the current status and can be restored.
 */
@ExtendWith(MockitoExtension.class)
class UpdateSubmissionSagaTest {

    @Mock
    private CommandBus commandBus;

    @Mock
    private QueryBus queryBus;

    private UpdateSubmissionSaga saga;

    @BeforeEach
    void setUp() {
        saga = new UpdateSubmissionSaga(commandBus, queryBus);
    }

    @Test
    void update_keepsTheCurrentStatus() {
        UUID id = UUID.randomUUID();
        SubmissionDTO current = new SubmissionDTO(id);
        current.setSubmissionStatus(SubmissionDTO.SubmissionStatusEnum.RECEIVED);
        SubmissionDTO incoming = new SubmissionDTO();
        incoming.setSubmissionStatus(SubmissionDTO.SubmissionStatusEnum.QUOTED);
        when(commandBus.send(any(ApplySubmissionStatusCommand.class))).thenAnswer(invocation ->
                Mono.just(invocation.getArgument(0, ApplySubmissionStatusCommand.class).record()));

        ExecutionContext ctx = ExecutionContext.forSaga(SubmissionSagaConstants.SAGA_UPDATE_RECORD, "test");
        ctx.putVariable(SubmissionSagaConstants.CTX_RECORD, current);

        StepVerifier.create(saga.updateSubmission(new UpdateSubmissionCommand(id, incoming), ctx))
                .assertNext(saved -> assertThat(saved.getSubmissionStatus()).isEqualTo(SubmissionDTO.SubmissionStatusEnum.RECEIVED))
                .verifyComplete();

        ArgumentCaptor<ApplySubmissionStatusCommand> captor = ArgumentCaptor.forClass(ApplySubmissionStatusCommand.class);
        verify(commandBus).send(captor.capture());
        assertThat(captor.getValue().submissionId()).isEqualTo(id);
        assertThat(captor.getValue().record().getSubmissionStatus()).isEqualTo(SubmissionDTO.SubmissionStatusEnum.RECEIVED);
        assertThat(ctx.getVariableAs(SubmissionSagaConstants.CTX_PREVIOUS_RECORD, SubmissionDTO.class)).isSameAs(current);
    }

    @Test
    void restore_writesThePreviousRecordBack() {
        UUID id = UUID.randomUUID();
        SubmissionDTO previous = new SubmissionDTO(id);
        previous.setSubmissionStatus(SubmissionDTO.SubmissionStatusEnum.RECEIVED);
        SubmissionDTO updated = new SubmissionDTO(id);
        updated.setSubmissionStatus(SubmissionDTO.SubmissionStatusEnum.QUOTED);
        when(commandBus.send(any(ApplySubmissionStatusCommand.class))).thenReturn(Mono.just(previous));

        ExecutionContext ctx = ExecutionContext.forSaga(SubmissionSagaConstants.SAGA_UPDATE_RECORD, "test");
        ctx.putVariable(SubmissionSagaConstants.CTX_PREVIOUS_RECORD, previous);

        StepVerifier.create(saga.restoreSubmission(updated, ctx))
                .verifyComplete();

        ArgumentCaptor<ApplySubmissionStatusCommand> captor = ArgumentCaptor.forClass(ApplySubmissionStatusCommand.class);
        verify(commandBus).send(captor.capture());
        assertThat(captor.getValue().record()).isSameAs(previous);
    }
}
