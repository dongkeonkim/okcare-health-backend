package com.okcare.assignment.health.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.okcare.assignment.health.domain.SaveResult;

/** 저장 응답 계약. */
public record HealthDataSaveResponse(
        @JsonProperty("recordkey") String recordKey,
        int received,
        int inserted,
        int updated,
        int duplicated
) {

    public static HealthDataSaveResponse from(String recordKey, SaveResult result) {
        return new HealthDataSaveResponse(
                recordKey,
                result.received(),
                result.inserted(),
                result.updated(),
                result.duplicated());
    }
}
