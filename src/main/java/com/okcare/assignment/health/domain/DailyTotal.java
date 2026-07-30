package com.okcare.assignment.health.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 반올림하지 않은 일별 합계.
 *
 * <p>응답 정밀도로 줄이지 않은 합계를 전달. 일별로 반올림한 값을 월별로 다시 더하면 기능 명세가
 * 못 박아 둔 월간 기대값과 불일치. 걸음수는 하루치가 ±1, 칼로리와 거리는 소수점 여섯째 자리가
 * 어긋나 눈으로 걸러지지 않음.
 */
public record DailyTotal(
        LocalDate date, BigDecimal steps, BigDecimal calories, BigDecimal distance) {

    /** 데이터가 없는 날. 조회 범위의 모든 날짜를 응답에 넣기로 했으므로 필요. */
    public static DailyTotal empty(LocalDate date) {
        return new DailyTotal(date, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
