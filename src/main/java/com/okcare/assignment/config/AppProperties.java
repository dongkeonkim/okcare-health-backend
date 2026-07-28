package com.okcare.assignment.config;

import jakarta.validation.constraints.NotNull;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 애플리케이션 고유 설정.
 *
 * <p>{@code businessZone}은 Daily/Monthly 집계의 기준 타임존. 코드 상수로 두지 않는 이유는 집계
 * 결과를 바꾸는 값이라 배포 없이 바로잡을 수 있어야 하기 때문.
 *
 * <p>{@code String}이 아니라 {@link ZoneId}로 바인딩해 잘못된 타임존 문자열이 기동 시점에 걸리게
 * 함. 문자열로 받으면 첫 집계 요청에서야 실패.
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(@NotNull ZoneId businessZone) {}
