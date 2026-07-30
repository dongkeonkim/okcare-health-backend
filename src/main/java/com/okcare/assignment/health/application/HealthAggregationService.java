package com.okcare.assignment.health.application;

import com.okcare.assignment.common.error.BusinessException;
import com.okcare.assignment.common.error.ErrorCode;
import com.okcare.assignment.health.domain.DailyTotal;
import com.okcare.assignment.health.domain.HealthConnection;
import com.okcare.assignment.health.domain.MonthlyTotal;
import com.okcare.assignment.health.infrastructure.HealthActivityRecordRepository;
import com.okcare.assignment.health.infrastructure.HealthConnectionRepository;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 집계 조회.
 *
 * <p>반올림하지 않은 합계까지만 만듦. 응답 정밀도로 줄이는 것은 응답 객체가 함. 여기서 줄이면 월간
 * 집계가 일별 반올림값을 더하는 길이 열리고, 그러면 기능 명세의 월간 기대값과 어긋남.
 */
@Service
@Transactional(readOnly = true)
public class HealthAggregationService {

    /**
     * 조회할 수 있는 최대 일수.
     *
     * <p>데이터가 없는 날짜까지 채워 응답하므로 상한이 없으면 응답 크기를 요청이 정함. 1년치
     * 그래프를 덮는 값으로 둠.
     */
    static final int MAX_DAYS = 366;

    /**
     * 조회할 수 있는 최대 월수. 상한을 두는 이유는 {@link #MAX_DAYS}와 같음. 2년 비교를 덮는 값.
     */
    static final int MAX_MONTHS = 24;

    private final HealthConnectionRepository connectionRepository;
    private final HealthActivityRecordRepository recordRepository;

    public HealthAggregationService(
            HealthConnectionRepository connectionRepository,
            HealthActivityRecordRepository recordRepository) {
        this.connectionRepository = connectionRepository;
        this.recordRepository = recordRepository;
    }

    /**
     * @throws BusinessException 범위가 뒤집혔거나 상한을 넘을 때, 조회할 수 없는
     *     {@code recordkey}일 때
     */
    public List<DailyTotal> daily(long memberId, String recordKey, LocalDate from, LocalDate to) {
        int days = requireValidRange(from, to);
        long connectionId = requireOwnedConnection(memberId, recordKey);

        return fillMissingDates(
                from, days, recordRepository.sumDailyTotals(connectionId, from, to));
    }

    /**
     * @throws BusinessException 범위가 뒤집혔거나 상한을 넘을 때, 조회할 수 없는
     *     {@code recordkey}일 때
     */
    public List<MonthlyTotal> monthly(
            long memberId, String recordKey, YearMonth from, YearMonth to) {

        int months = requireValidRange(from, to);
        long connectionId = requireOwnedConnection(memberId, recordKey);

        // 시작 월의 1일부터 종료 월의 말일까지. 말일 계산을 YearMonth에 맡겨 2월과 윤년을 따로
        // 다루지 않음.
        List<MonthlyTotal> found =
                recordRepository.sumMonthlyTotals(
                        connectionId, from.atDay(1), to.atEndOfMonth());

        return fillMissingMonths(from, months, found);
    }

    private static int requireValidRange(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        // 양 끝을 모두 포함하므로 +1. 같은 날짜를 넣으면 1일.
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > MAX_DAYS) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        return (int) days;
    }

    private static int requireValidRange(YearMonth from, YearMonth to) {
        if (from.isAfter(to)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        long months = ChronoUnit.MONTHS.between(from, to) + 1;
        if (months > MAX_MONTHS) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        return (int) months;
    }

    private long requireOwnedConnection(long memberId, String recordKey) {
        return connectionRepository
                .findByRecordKeyAndMemberId(recordKey, memberId)
                .map(HealthConnection::getId)
                .orElseThrow(() -> new BusinessException(ErrorCode.HEALTH_RECORD_KEY_NOT_FOUND));
    }

    /**
     * 조회 범위의 모든 날짜를 채움. 데이터가 없는 날은 0.
     *
     * <p>범위를 순서대로 훑으므로 정렬도 여기서 정해짐. 조회 쿼리에 {@code order by}를 걸지 않는
     * 이유가 이것.
     */
    private static List<DailyTotal> fillMissingDates(
            LocalDate from, int days, List<DailyTotal> found) {

        Map<LocalDate, DailyTotal> byDate = new HashMap<>();
        found.forEach(total -> byDate.put(total.date(), total));

        List<DailyTotal> filled = new ArrayList<>(days);
        for (int offset = 0; offset < days; offset++) {
            LocalDate date = from.plusDays(offset);
            filled.add(byDate.getOrDefault(date, DailyTotal.empty(date)));
        }

        return filled;
    }

    /** 조회 범위의 모든 월을 채움. 정렬을 여기서 정하는 이유는 {@link #fillMissingDates}와 같음. */
    private static List<MonthlyTotal> fillMissingMonths(
            YearMonth from, int months, List<MonthlyTotal> found) {

        Map<YearMonth, MonthlyTotal> byMonth = new HashMap<>();
        found.forEach(total -> byMonth.put(total.month(), total));

        List<MonthlyTotal> filled = new ArrayList<>(months);
        for (int offset = 0; offset < months; offset++) {
            YearMonth month = from.plusMonths(offset);
            filled.add(byMonth.getOrDefault(month, MonthlyTotal.empty(month)));
        }

        return filled;
    }
}
