package com.okcare.assignment.health.api;

import com.okcare.assignment.health.domain.DailyTotal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/** 일간 조회 응답 계약. 반올림은 응답 변환 단계에서만 수행. */
public record DailyAggregationResponse(String recordKey, String zoneId, List<Item> items) {

    private static final int OUTPUT_SCALE = 6;

    public static DailyAggregationResponse of(
            String recordKey, ZoneId zone, List<DailyTotal> totals) {

        return new DailyAggregationResponse(
                recordKey, zone.getId(), totals.stream().map(Item::from).toList());
    }

    /**
     * {@code steps}를 {@code BigDecimal}이 아니라 {@code long}으로 둠.
     *
     * <p>{@code BigDecimal}로 두면 scale에 따라 {@code 7243.000000000000}이나 {@code 7243.0}으로
     * 직렬화될 수 있음. 반올림 결과가 정수라는 계약을 타입으로 못 박음.
     *
     * <p>반대로 칼로리와 거리는 {@code BigDecimal}로 둬야 함. {@code double}로 바꾸면 소수점 여섯
     * 자리를 정확히 표현할 수 없고 {@code 0.000000}이 {@code 0.0}이 됨.
     */
    public record Item(LocalDate date, long steps, BigDecimal calories, BigDecimal distance) {

        static Item from(DailyTotal total) {
            return new Item(
                    total.date(),
                    total.steps().setScale(0, RoundingMode.HALF_UP).longValueExact(),
                    total.calories().setScale(OUTPUT_SCALE, RoundingMode.HALF_UP),
                    total.distance().setScale(OUTPUT_SCALE, RoundingMode.HALF_UP));
        }
    }
}
