package com.okcare.assignment.config;

import jakarta.validation.constraints.NotNull;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 애플리케이션 고유 설정이다.
 *
 * <p>{@code businessZone}은 Daily/Monthly 집계의 기준 타임존이다. 기능_명세.md §5.2가
 * {@code Asia/Seoul}을 집계 기준으로 정했고, 개발_계획.md §6.2가 이 값을 코드 상수가 아니라
 * 검증된 설정값으로 관리하도록 요구한다.
 *
 * <p>{@link ZoneId}로 직접 바인딩하므로 잘못된 타임존 문자열은 기동 시점에 실패한다.
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(@NotNull ZoneId businessZone) {}
