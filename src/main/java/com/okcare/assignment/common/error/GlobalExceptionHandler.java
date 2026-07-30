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
 * <p>{@link ResponseEntityExceptionHandler}를 상속해 지원하지 않는 Content-Type, 허용되지 않는
 * 메서드처럼 Spring MVC가 이미 4xx로 분류한 예외의 상태를 보존. 상속하지 않고 {@code Exception}
 * 처리기만 두면 그 예외들이 전부 500으로 바뀐다.
 *
 * <p>어느 경로에서도 예외 객체를 로거에 넘기지 않는다. 예외 메시지와 stack trace에는 평문
 * 비밀번호와 요청 본문 일부가 섞여 들어온다. 원인 추적은 {@code traceId}와 예외 타입으로 한다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    /** 요청 DTO 검증 실패를 400과 필드 목록으로 변환. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        List<ErrorResponse.FieldError> fieldErrors =
                ex.getBindingResult().getFieldErrors().stream()
                        // getRejectedValue()를 쓰지 않음. 비밀번호 위반이면 평문이 그대로 담김.
                        .map(
                                error ->
                                        new ErrorResponse.FieldError(
                                                error.getField(), error.getDefaultMessage()))
                        // 제약 조건 평가 순서는 보장되지 않음. 정렬해야 응답이 안정적.
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

    /**
     * 나머지 Spring MVC 예외를 프레임워크가 정한 상태 그대로 내보낸다.
     *
     * <p>코드만 상태 계열에 맞춰 고른다. 여기서 {@link ErrorCode#status()}를 쓰면 415나 405가
     * 400으로 바뀌어 원래 의미를 잃는다.
     */
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

    /** 기능 계약이 정의한 실패를 해당 상태로 변환. */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
        String traceId = newTraceId();
        log.warn("기능 계약 위반 traceId={} code={}", traceId, e.errorCode().name());

        return ResponseEntity.status(e.errorCode().status())
                .body(ErrorResponse.of(e.errorCode(), traceId, clock.instant()));
    }

    /** 예상하지 못한 오류를 500으로 변환. */
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
