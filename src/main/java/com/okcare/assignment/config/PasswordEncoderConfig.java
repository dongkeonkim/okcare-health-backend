package com.okcare.assignment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class PasswordEncoderConfig {

    /**
     * 비밀번호 인코더를 빈으로 노출.
     *
     * <p>{@code spring-security-crypto}만으로 구성. {@code spring-boot-starter-security}는 서블릿
     * 필터 체인을 자동 구성해 {@code /actuator/health}까지 차단하고, 그러면 Compose healthcheck가
     * 실패해 컨테이너 전체가 unhealthy가 됨. 필터 체인은 {@code Authorization} 헤더를 검증하는
     * 경로가 생길 때 health를 열어 주는 설정과 함께 추가. 로그인은 자격 증명을 본문으로 받으므로
     * 아직 필요하지 않음.
     *
     * <p>{@code DelegatingPasswordEncoder}는 해시에 {@code {bcrypt}} 접두사를 남기므로 나중에
     * 알고리즘을 바꿔도 기존 비밀번호를 그대로 검증 가능.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
