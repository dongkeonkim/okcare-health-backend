package com.okcare.assignment.health.api;

import com.okcare.assignment.health.domain.SaveResult;

/**
 * 저장 응답 계약.
 * 요청의 {@code recordkey}와 달리 응답은 {@code recordKey} 사용.
 */
public record HealthDataSaveResponse(
        String recordKey, int received, int inserted, int updated, int duplicated) {

    public static HealthDataSaveResponse from(String recordKey, SaveResult result) {
        return new HealthDataSaveResponse(
                recordKey,
                result.received(),
                result.inserted(),
                result.updated(),
                result.duplicated());
    }
}
