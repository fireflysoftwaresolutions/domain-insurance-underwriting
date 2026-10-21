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

package io.fireflysoftware.domain.insurance.underwriting.core.lifecycle;

import io.fireflysoftware.core.insurance.underwriting.sdk.model.SubmissionDTO;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks the domain copy of the core lifecycle.
 */
class SubmissionRecordLifecycleTest {

    @Test
    void allowsTheCoreMove() {
        assertThat(SubmissionRecordLifecycle.canTransition(SubmissionDTO.SubmissionStatusEnum.RECEIVED, SubmissionDTO.SubmissionStatusEnum.IN_CLEARANCE)).isTrue();
    }

    @Test
    void refusesAMoveTheCoreDoesNotAllow() {
        assertThat(SubmissionRecordLifecycle.canTransition(SubmissionDTO.SubmissionStatusEnum.RECEIVED, SubmissionDTO.SubmissionStatusEnum.BOUND)).isFalse();
    }

    @Test
    void removeIsLimitedToTheDeletableStatus() {
        assertThat(SubmissionRecordLifecycle.canRemove(null)).isTrue();
        assertThat(SubmissionRecordLifecycle.canRemove(SubmissionDTO.SubmissionStatusEnum.RECEIVED)).isTrue();
        assertThat(SubmissionRecordLifecycle.canRemove(SubmissionDTO.SubmissionStatusEnum.QUOTED)).isFalse();
    }
}
