package com.okcare.assignment.health.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.okcare.assignment.health.domain.MonthlyTotal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

/** 월간 조회 응답 계약. 반올림은 응답 변환 단계에서만 수행. */
public record MonthlyAggregationResponse(
        @JsonProperty("recordkey") String recordKey,
        String zoneId,
        List<Item> items
) {

    private static final int OUTPUT_SCALE = 6;

    public static MonthlyAggregationResponse of(
            String recordKey, ZoneId zone, List<MonthlyTotal> totals) {

        return new MonthlyAggregationResponse(
                recordKey, zone.getId(), totals.stream().map(Item::from).toList());
    }

    public record Item(YearMonth month, long steps, BigDecimal calories, BigDecimal distance) {

        static Item from(MonthlyTotal total) {
            return new Item(
                    total.month(),
                    total.steps().setScale(0, RoundingMode.HALF_UP).longValueExact(),
                    total.calories().setScale(OUTPUT_SCALE, RoundingMode.HALF_UP),
                    total.distance().setScale(OUTPUT_SCALE, RoundingMode.HALF_UP));
        }
    }
}
