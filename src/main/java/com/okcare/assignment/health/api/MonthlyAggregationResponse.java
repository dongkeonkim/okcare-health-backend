package com.okcare.assignment.health.api;

import com.okcare.assignment.health.domain.MonthlyTotal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

/**
 * 필드 이름과 순서는 기능 명세의 월간 조회 응답 계약.
 *
 * <p>반올림이 이 클래스에서만 일어남. 이유는 {@link DailyAggregationResponse}와 같음.
 */
public record MonthlyAggregationResponse(String recordKey, String zoneId, List<Item> items) {

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
