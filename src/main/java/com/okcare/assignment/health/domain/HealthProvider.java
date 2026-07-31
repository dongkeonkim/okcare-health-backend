package com.okcare.assignment.health.domain;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Arrays;
import java.util.Optional;

/** 지원 공급자와 측정 구간 형식. 공급자 이름으로 명시적으로 판별. */
public enum HealthProvider {

    /** 측정 구간에 타임존이 없어 사업 기준 타임존으로 해석. */
    SAMSUNG_HEALTH("SamsungHealth", strict("uuuu-MM-dd HH:mm:ss"), false),

    /** 측정 구간이 콜론 없는 오프셋을 담음. 예: {@code 2024-11-14T21:20:00+0000} */
    HEALTH_KIT("Health Kit", strict("uuuu-MM-dd'T'HH:mm:ssZ"), true);

    /** 양쪽 공급자가 공유하는 {@code lastUpdate} 형식. 측정 구간과 또 다름. */
    private static final DateTimeFormatter LAST_UPDATE_FORMAT = strict("uuuu-MM-dd HH:mm:ss Z");

    private final String sourceName;
    private final DateTimeFormatter periodFormat;
    private final boolean periodCarriesOffset;

    HealthProvider(String sourceName, DateTimeFormatter periodFormat, boolean periodCarriesOffset) {
        this.sourceName = sourceName;
        this.periodFormat = periodFormat;
        this.periodCarriesOffset = periodCarriesOffset;
    }

    /**
     * 달력상 존재하지 않는 값도 거부하는 STRICT 포매터.
     *
     * <p>{@code SMART}의 자동 보정과 {@code yyyy} 사용을 피함.
     * {@code uuuu}로 연도 해석을 고정.
     */
    private static DateTimeFormatter strict(String pattern) {
        return DateTimeFormatter.ofPattern(pattern).withResolverStyle(ResolverStyle.STRICT);
    }

    public static Optional<HealthProvider> bySourceName(String sourceName) {
        return Arrays.stream(values())
                .filter(provider -> provider.sourceName.equals(sourceName))
                .findFirst();
    }

    /**
     * 측정 구간 시각을 UTC 순간으로 변환.
     *
     * @param fallbackZone 오프셋이 없는 형식에만 적용. 오프셋을 담는 공급자는 무시
     * @throws DateTimeParseException 이 공급자의 형식이 아닐 때
     */
    public Instant toInstant(String value, ZoneId fallbackZone) {
        if (periodCarriesOffset) {
            return OffsetDateTime.parse(value, periodFormat).toInstant();
        }

        // 타임존의 겹침·공백 기본 해석으로 집계 날짜가 바뀔 수 있음.
        return LocalDateTime.parse(value, periodFormat).atZone(fallbackZone).toInstant();
    }

    /** 공급자와 무관하게 같은 형식이라 인스턴스 메서드로 두지 않음. */
    public static Instant parseLastUpdate(String value) {
        return OffsetDateTime.parse(value, LAST_UPDATE_FORMAT).toInstant();
    }
}
