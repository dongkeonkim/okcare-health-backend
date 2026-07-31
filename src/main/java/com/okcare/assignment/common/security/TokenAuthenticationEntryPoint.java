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
 * 인증 필터 단계의 401을 공통 오류 응답으로 변환.
 * 예외 객체는 토큰 노출 방지를 위해 로그에서 제외.
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
