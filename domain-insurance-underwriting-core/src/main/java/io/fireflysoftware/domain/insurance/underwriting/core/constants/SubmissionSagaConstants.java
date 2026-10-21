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

package io.fireflysoftware.domain.insurance.underwriting.core.constants;

/**
 * Names shared by the submission sagas, their steps, and the saga engine.
 */
public final class SubmissionSagaConstants {

    public static final String SAGA_REGISTER = "RegisterSubmissionSaga";
    public static final String SAGA_UPDATE_STATUS = "UpdateSubmissionStatusSaga";

    public static final String STEP_REGISTER = "registerSubmission";
    public static final String STEP_RECORD_INITIAL_STATUS = "recordInitialStatus";
    public static final String STEP_RETRIEVE = "retrieveSubmission";
    public static final String STEP_UPDATE_STATUS = "updateSubmissionStatus";
    public static final String STEP_RECORD_STATUS_HISTORY = "recordStatusHistory";

    public static final String COMPENSATE_REMOVE = "removeSubmission";
    public static final String COMPENSATE_REMOVE_INITIAL_STATUS = "removeInitialStatus";
    public static final String COMPENSATE_RESTORE = "restoreSubmissionStatus";
    public static final String COMPENSATE_REMOVE_STATUS_HISTORY = "removeStatusHistory";

    public static final String EVENT_REGISTERED = "submissions.registered";
    public static final String EVENT_INITIAL_STATUS_RECORDED = "submissions.status.recorded";
    public static final String EVENT_RETRIEVED = "submissions.retrieved";
    public static final String EVENT_STATUS_UPDATED = "submissions.status.updated";
    public static final String EVENT_STATUS_HISTORY_RECORDED = "submissions.status.history.recorded";

    public static final String CTX_RECORD = "submissionIdRecord";
    public static final String CTX_PREVIOUS_STATUS = "previousSubmissionStatus";

    private SubmissionSagaConstants() {
    }
}
