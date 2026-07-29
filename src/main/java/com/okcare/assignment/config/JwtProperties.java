package com.okcare.assignment.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * JWT 서명 설정.
 *
 * <p>{@link AppProperties}에 합치지 않음. secret을 담는 객체는 로그, 오류 메시지와 actuator에
 * 노출하면 안 되는데, 집계 기준 타임존까지 같은 제약에 묶일 이유가 없음.
 *
 * <p>토큰 유효시간은 여기에 두지 않음. 기능 명세가 응답 계약으로 고정한 값이라 설정으로 열면
 * 문서에 적힌 만료값과 실제 토큰이 조용히 어긋남.
 *
 * <p>{@link NotBlank}만으로는 부족. 환경변수가 없으면 placeholder가 리터럴 문자열로 바인딩되어
 * 이 검증을 통과함. 존재 여부는 {@link RequiredEnvironmentValidator}, 값의 안전 기준은 서명 키를
 * 만드는 시점에 검사.
 */
@Validated
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(@NotBlank String secret) {}
