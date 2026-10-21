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
import io.fireflysoftware.domain.insurance.underwriting.core.constants.SubmissionSagaConstants;

import io.fireflysoftware.core.insurance.underwriting.sdk.model.SubmissionStatusHistoryDTO;
import io.fireflysoftware.domain.insurance.underwriting.core.commands.RecordSubmissionStatusHistoryCommand;

import org.fireflyframework.cqrs.command.CommandBus;
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
 * Verifies the register saga stores the core result and can compensate by deleting it.
 */
@ExtendWith(MockitoExtension.class)
class RegisterSubmissionSagaTest {

    @Mock
    private CommandBus commandBus;

    private RegisterSubmissionSaga saga;

    @BeforeEach
    void setUp() {
        saga = new RegisterSubmissionSaga(commandBus);
    }

    @Test
    void register_storesTheRecordReturnedByTheCore() {
        UUID id = UUID.randomUUID();
        SubmissionDTO created = new SubmissionDTO(id);
        created.setSubmissionStatus(SubmissionDTO.SubmissionStatusEnum.RECEIVED);
        when(commandBus.send(any(CreateSubmissionCommand.class))).thenReturn(Mono.just(created));

        ExecutionContext ctx = ExecutionContext.forSaga(SubmissionSagaConstants.SAGA_REGISTER, "test");
        SubmissionDTO request = new SubmissionDTO();
        request.setSubmissionStatus(SubmissionDTO.SubmissionStatusEnum.RECEIVED);

        StepVerifier.create(saga.registerSubmission(new CreateSubmissionCommand(request), ctx))
                .expectNext(created)
                .verifyComplete();

        assertThat(ctx.getVariableAs(SubmissionSagaConstants.CTX_RECORD, SubmissionDTO.class).getSubmissionId()).isEqualTo(id);
    }

    @Test
    void remove_sendsDeleteForTheCreatedRecord() {
        UUID id = UUID.randomUUID();
        when(commandBus.send(any(RemoveSubmissionCommand.class))).thenReturn(Mono.empty());

        StepVerifier.create(saga.removeSubmission(new SubmissionDTO(id)))
                .verifyComplete();

        ArgumentCaptor<RemoveSubmissionCommand> captor = ArgumentCaptor.forClass(RemoveSubmissionCommand.class);
        verify(commandBus).send(captor.capture());
        assertThat(captor.getValue().submissionId()).isEqualTo(id);
    }

    @Test
    void recordInitialStatus_copiesIdAndStatusFromTheCreatedRecord() {
        UUID id = UUID.randomUUID();
        SubmissionDTO created = new SubmissionDTO(id);
        created.setSubmissionStatus(SubmissionDTO.SubmissionStatusEnum.RECEIVED);
        SubmissionStatusHistoryDTO saved = new SubmissionStatusHistoryDTO(UUID.randomUUID());
        when(commandBus.send(any(RecordSubmissionStatusHistoryCommand.class))).thenReturn(Mono.just(saved));

        ExecutionContext ctx = ExecutionContext.forSaga(SubmissionSagaConstants.SAGA_REGISTER, "test");
        ctx.putVariable(SubmissionSagaConstants.CTX_RECORD, created);

        StepVerifier.create(saga.recordInitialStatus(new CreateSubmissionCommand(created), ctx))
                .expectNext(saved)
                .verifyComplete();

        ArgumentCaptor<RecordSubmissionStatusHistoryCommand> captor = ArgumentCaptor.forClass(RecordSubmissionStatusHistoryCommand.class);
        verify(commandBus).send(captor.capture());
        assertThat(captor.getValue().history().getSubmissionId()).isEqualTo(id);
        assertThat(captor.getValue().history().getSubmissionStatus().getValue()).isEqualTo("RECEIVED");
    }

}
