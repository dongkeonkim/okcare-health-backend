package com.okcare.assignment.health.domain;

import java.math.BigDecimal;
import java.time.YearMonth;

/**
 * 반올림하지 않은 월별 합계.
 *
 * <p>일별 합계를 다시 더해 만들지 않고 전용 쿼리가 원본 행에서 직접 집계. 이유는
 * {@link DailyTotal}과 같음.
 */
public record MonthlyTotal(
        YearMonth month, BigDecimal steps, BigDecimal calories, BigDecimal distance) {

    /**
     * JPQL 생성자 표현식용.
     *
     * <p>{@code YearMonth}를 JPQL에서 만들 수 없어 연·월을 숫자로 받는 생성자가 필요. 상자 타입으로
     * 받는 이유는 HQL의 {@code year()}, {@code month()}가 {@code Integer}를 돌려주고, 기본형으로
     * 선언하면 Hibernate가 생성자를 찾지 못할 수 있기 때문.
     */
    public MonthlyTotal(
            Integer year,
            Integer month,
            BigDecimal steps,
            BigDecimal calories,
            BigDecimal distance) {
        this(YearMonth.of(year, month), steps, calories, distance);
    }

    /** 데이터가 없는 월. 조회 범위의 모든 월을 응답에 넣기로 했으므로 필요. */
    public static MonthlyTotal empty(YearMonth month) {
        return new MonthlyTotal(month, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
