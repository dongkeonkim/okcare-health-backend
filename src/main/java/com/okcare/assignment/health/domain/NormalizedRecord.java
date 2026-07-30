package com.okcare.assignment.health.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 공급자 차이를 걷어낸 활동 레코드 한 건.
 *
 * <p>{@code payloadHash}는 측정값과 단위만으로 계산한 값. 같은 식별자의 값이 바뀌었는지 판정하는
 * 용도.
 *
 * <p>측정값은 정규화 계층에서 저장 정밀도로 이미 맞춘 값. 기능 명세가 정한 집계 출력 반올림(걸음수
 * 정수, 칼로리·거리 여섯 자리)은 이것과 별개이며 집계 단계에서 적용.
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
