package com.okcare.assignment.health.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 반올림하지 않은 일별 합계.
 * 일별 반올림값을 월간 합산하지 않도록 집계 단계의 원본으로 사용.
 */
public record DailyTotal(
        LocalDate date, BigDecimal steps, BigDecimal calories, BigDecimal distance) {

    /** 데이터가 없는 날. 조회 범위의 모든 날짜를 응답에 넣기로 했으므로 필요. */
    public static DailyTotal empty(LocalDate date) {
        return new DailyTotal(date, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
