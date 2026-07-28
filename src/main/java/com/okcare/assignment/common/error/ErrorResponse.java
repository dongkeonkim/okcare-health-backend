package com.okcare.assignment.common.error;

import java.time.Instant;
import java.util.List;

/**
 * 거부된 입력값은 담지 않음. 검증 실패 필드에 평문 비밀번호가 그대로 들어오므로, 값을 실으면
 * 오류 응답으로 비밀번호가 새어 나감.
 */
public record ErrorResponse(
        String code,
        String message,
        List<FieldError> fieldErrors,
        String traceId,
        Instant timestamp) {

    public record FieldError(String field, String message) {}

    public static ErrorResponse of(ErrorCode errorCode, String traceId, Instant timestamp) {
        return new ErrorResponse(
                errorCode.name(), errorCode.message(), List.of(), traceId, timestamp);
    }

    public static ErrorResponse of(
            ErrorCode errorCode, List<FieldError> fieldErrors, String traceId, Instant timestamp) {
        return new ErrorResponse(
                errorCode.name(),
                errorCode.message(),
                List.copyOf(fieldErrors),
                traceId,
                timestamp);
    }
}
