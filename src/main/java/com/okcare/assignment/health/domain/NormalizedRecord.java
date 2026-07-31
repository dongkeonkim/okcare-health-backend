package com.okcare.assignment.health.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 공급자 차이를 걷어낸 활동 레코드.
 * 해시는 측정값 변경 감지에 사용하고 출력 반올림은 집계 단계에서 수행.
 */
public record NormalizedRecord(
        String metricType,
        Instant periodStart,
        Instant periodEnd,
        LocalDate activityDate,
        BigDecimal steps,
        BigDecimal calories,
        BigDecimal distance,
        String caloriesUnit,
        String distanceUnit,
        String payloadHash) {

    /** 같은 레코드인지 판정하는 기준. 데이터베이스 UNIQUE 제약과 같은 조합. */
    public Identity identity() {
        return new Identity(metricType, periodStart, periodEnd);
    }

    public record Identity(String metricType, Instant periodStart, Instant periodEnd) {}
}
