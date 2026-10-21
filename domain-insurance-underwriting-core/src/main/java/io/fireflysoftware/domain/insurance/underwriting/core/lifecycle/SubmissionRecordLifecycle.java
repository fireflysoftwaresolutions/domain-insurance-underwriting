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

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Moves copied from the submission lifecycle on {@code core-insurance-underwriting}.
 * A missing status is treated as RECEIVED, which is the only status the core will delete.
 */
public final class SubmissionRecordLifecycle {

    private static final Map<SubmissionDTO.SubmissionStatusEnum, Set<SubmissionDTO.SubmissionStatusEnum>> ALLOWED = Map.of(
            SubmissionDTO.SubmissionStatusEnum.RECEIVED, EnumSet.of(SubmissionDTO.SubmissionStatusEnum.IN_CLEARANCE, SubmissionDTO.SubmissionStatusEnum.DECLINED, SubmissionDTO.SubmissionStatusEnum.WITHDRAWN),
            SubmissionDTO.SubmissionStatusEnum.IN_CLEARANCE, EnumSet.of(SubmissionDTO.SubmissionStatusEnum.REFERRED, SubmissionDTO.SubmissionStatusEnum.QUOTED, SubmissionDTO.SubmissionStatusEnum.DECLINED, SubmissionDTO.SubmissionStatusEnum.WITHDRAWN),
            SubmissionDTO.SubmissionStatusEnum.REFERRED, EnumSet.of(SubmissionDTO.SubmissionStatusEnum.IN_CLEARANCE, SubmissionDTO.SubmissionStatusEnum.QUOTED, SubmissionDTO.SubmissionStatusEnum.DECLINED, SubmissionDTO.SubmissionStatusEnum.WITHDRAWN),
            SubmissionDTO.SubmissionStatusEnum.QUOTED, EnumSet.of(SubmissionDTO.SubmissionStatusEnum.BOUND, SubmissionDTO.SubmissionStatusEnum.DECLINED, SubmissionDTO.SubmissionStatusEnum.WITHDRAWN)
    );

    private SubmissionRecordLifecycle() {
    }

    public static boolean canRemove(SubmissionDTO.SubmissionStatusEnum status) {
        return status == null || status == SubmissionDTO.SubmissionStatusEnum.RECEIVED;
    }

    public static boolean canTransition(SubmissionDTO.SubmissionStatusEnum from, SubmissionDTO.SubmissionStatusEnum to) {
        if (from == null || to == null) {
            return false;
        }
        if (from == to) {
            return true;
        }
        Set<SubmissionDTO.SubmissionStatusEnum> next = ALLOWED.get(from);
        return next != null && next.contains(to);
    }
}
