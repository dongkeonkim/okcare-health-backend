package com.okcare.assignment.common.security;

import com.okcare.assignment.auth.infrastructure.JwtTokenProvider;
import com.okcare.assignment.common.error.BusinessException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bearer 액세스 토큰으로 인증 주체를 세우는 필터.
 *
 * <p>검증 실패를 여기서 응답으로 바꾸지 않고 인증 주체를 세우지 않은 채 통과시킴. 보호된 경로면
 * 뒤따르는 인가 단계가 {@link TokenAuthenticationEntryPoint}로 401을 만들고, 공개 경로면 잘못된
 * 헤더가 붙어 있어도 정상 처리됨. 필터에서 직접 응답을 쓰면 두 경우를 구분하려고 경로 목록을
 * 필터와 설정 양쪽에 두게 됨.
 *
 * <p>주체를 {@code Long}으로 둠. 회원 식별자 외에 필요한 것이 없어 별도 타입을 만들지 않음.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String token = bearerToken(request);

        if (token != null) {
            authenticate(token);
        }

        filterChain.doFilter(request, response);
    }

    private void authenticate(String token) {
        try {
            long memberId = jwtTokenProvider.parseAccessToken(token);

            SecurityContextHolder.getContext()
                    .setAuthentication(
                            new UsernamePasswordAuthenticationToken(memberId, null, List.of()));
        } catch (BusinessException e) {
            // 인증 주체를 세우지 않고 넘김. 예외 메시지를 로그에 남기지 않는 이유는 제시된 토큰
            // 문자열이 섞여 들어오기 때문.
            SecurityContextHolder.clearContext();
        }
    }

    private static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }

        return header.substring(BEARER_PREFIX.length());
    }
}
