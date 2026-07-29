package com.okcare.assignment.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.okcare.assignment.common.error.ErrorCode;
import com.okcare.assignment.common.error.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * 인증 필터 단계의 401을 기능 명세의 오류 응답 형식으로 기록.
 *
 * <p>필터는 {@code DispatcherServlet} 앞에서 동작하므로 전역 예외 처리기가 이 실패를 받지 못함.
 * 이 진입점을 두지 않으면 헤더가 없는 요청에 Spring 기본 401(빈 본문과 {@code WWW-Authenticate})이
 * 나가고 오류 응답 형식이 엔드포인트마다 달라짐.
 *
 * <p>{@link AuthenticationException}을 로거에 넘기지 않음. 메시지에 제시된 토큰 문자열이 섞여
 * 들어옴.
 */
@Component
public class TokenAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(TokenAuthenticationEntryPoint.class);

    private final ObjectMapper objectMapper;
    private final Clock clock;

    public TokenAuthenticationEntryPoint(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException)
            throws IOException {

        String traceId = UUID.randomUUID().toString();
        log.warn(
                "인증 실패 traceId={} method={} type={}",
                traceId,
                request.getMethod(),
                authException.getClass().getSimpleName());

        ErrorCode errorCode = ErrorCode.AUTH_ACCESS_TOKEN_INVALID;

        response.setStatus(errorCode.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(
                response.getOutputStream(),
                ErrorResponse.of(errorCode, traceId, clock.instant()));
    }
}
