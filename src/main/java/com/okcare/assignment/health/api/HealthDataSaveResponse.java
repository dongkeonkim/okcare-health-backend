package com.okcare.assignment.health.api;

import com.okcare.assignment.health.domain.SaveResult;

/**
 * 필드 이름과 순서는 기능 명세의 저장 응답 계약.
 *
 * <p>요청은 {@code recordkey}, 응답은 {@code recordKey}로 명세가 다르게 정하므로 이름이 어긋나는
 * 것이 정상.
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
