package com.okcare.assignment.config;

import com.okcare.assignment.common.security.JwtAuthenticationFilter;
import com.okcare.assignment.common.security.TokenAuthenticationEntryPoint;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    /**
     * 상태가 없는 Bearer 토큰 인증 체인.
     *
     * <p>healthcheck와 인증 이전 엔드포인트는 공개.
     * 세션·CSRF·form/basic 인증은 비활성화해 토큰 기반 오류 형식 유지.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            TokenAuthenticationEntryPoint authenticationEntryPoint,
            WebEndpointProperties webEndpointProperties)
            throws Exception {

        String healthPath = webEndpointProperties.getBasePath() + "/health";

        return http.csrf(csrf -> csrf.disable())
                // 기본 LogoutFilter의 /logout 가로채기 방지.
                .logout(logout -> logout.disable())
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        requests ->
                                requests.requestMatchers(
                                                healthPath,
                                                "/api/v1/auth/signup",
                                                "/api/v1/auth/login",
                                                "/api/v1/auth/refresh")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .exceptionHandling(
                        handling -> handling.authenticationEntryPoint(authenticationEntryPoint))
                .addFilterBefore(
                        jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
