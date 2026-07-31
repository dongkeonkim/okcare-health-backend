package com.okcare.assignment.common.error;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 모든 오류를 하나의 응답 형식으로 변환.
 *
 * <p>Spring MVC의 4xx 상태 보존. 요청이 포함된 예외 객체는 로그에서 제외.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        List<ErrorResponse.FieldError> fieldErrors =
                ex.getBindingResult().getFieldErrors().stream()
                        // 거부된 값은 읽지 않아 비밀번호 등 입력값 노출 방지.
                        .map(
                                error ->
                                        new ErrorResponse.FieldError(
                                                error.getField(), error.getDefaultMessage()))
                        // 제약 조건 평가 순서가 비결정적이므로 응답 순서 정렬.
                        .sorted(
                                Comparator.comparing(ErrorResponse.FieldError::field)
                                        .thenComparing(ErrorResponse.FieldError::message))
                        .toList();

        String traceId = newTraceId();
        log.warn("요청 검증 실패 traceId={} fields={}", traceId, fieldErrors.size());

        return ResponseEntity.status(status)
                .body(
                        ErrorResponse.of(
                                ErrorCode.INVALID_REQUEST, fieldErrors, traceId, clock.instant()));
    }

    /** 나머지 Spring MVC 예외도 프레임워크가 정한 HTTP 상태로 응답. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex,
            @Nullable Object body,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {

        ErrorCode errorCode =
                status.is4xxClientError() ? ErrorCode.INVALID_REQUEST : ErrorCode.INTERNAL_ERROR;
        String traceId = newTraceId();
        log.warn(
                "요청 처리 실패 traceId={} status={} type={}",
                traceId,
                status.value(),
                ex.getClass().getSimpleName());

        return ResponseEntity.status(status)
                .body(ErrorResponse.of(errorCode, traceId, clock.instant()));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
        String traceId = newTraceId();
        log.warn("기능 계약 위반 traceId={} code={}", traceId, e.errorCode().name());

        return ResponseEntity.status(e.errorCode().status())
                .body(ErrorResponse.of(e.errorCode(), traceId, clock.instant()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        String traceId = newTraceId();
        log.error("예상하지 못한 서버 오류 traceId={} type={}", traceId, e.getClass().getName());

        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status())
                .body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR, traceId, clock.instant()));
    }

    private String newTraceId() {
        return UUID.randomUUID().toString();
    }
}
