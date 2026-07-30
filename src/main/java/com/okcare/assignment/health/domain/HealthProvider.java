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

/**
 * 지원하는 건강 데이터 공급자와 그 시각 형식.
 *
 * <p>공급자별 차이는 측정 구간 문자열이 오프셋을 담는지 하나뿐. 그래서 파싱 전략 객체를 따로 두지
 * 않고 형식과 플래그만 가짐.
 *
 * <p>{@code data.source.name}으로 판별하고 목록에 없으면 거부. 값의 모양으로 형식을 추론하면
 * 검증하지 않은 공급자의 시각 의미를 조용히 잘못 해석하게 됨.
 */
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
     * 달력상 존재하지 않는 값을 거부하는 포매터.
     *
     * <p>기본 {@code ResolverStyle.SMART}는 {@code 2024-02-30}을 {@code 2024-02-29}로,
     * {@code 24:00:00}을 다음 날 자정으로 조용히 보정. 400으로 거절해야 할 입력이 다른 절대
     * 시각과 다른 식별자로 저장되어 재전송 판정과 집계까지 오염됨.
     *
     * <p>{@code STRICT}는 연도 필드로 {@code yyyy}(연대 기준)를 받지 않으므로 {@code uuuu}를 써야
     * 함. {@code yyyy}를 그대로 두면 파싱이 전부 실패.
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

        // Asia/Seoul은 현재 일광절약시간이 없어 겹침·공백 구간이 생기지 않음. 다른 타임존으로
        // 바꾸면 atZone의 기본 해석(겹침은 이른 쪽, 공백은 뒤로 이동)이 집계 날짜를 바꿀 수 있음.
        return LocalDateTime.parse(value, periodFormat).atZone(fallbackZone).toInstant();
    }

    /** 공급자와 무관하게 같은 형식이라 인스턴스 메서드로 두지 않음. */
    public static Instant parseLastUpdate(String value) {
        return OffsetDateTime.parse(value, LAST_UPDATE_FORMAT).toInstant();
    }
}
