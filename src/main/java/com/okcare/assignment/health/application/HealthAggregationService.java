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

@Service
public class HealthAggregationService {

    /** 데이터가 없는 날짜까지 채우는 조회의 최대 일수. */
    static final int MAX_DAYS = 366;

    /** 데이터가 없는 월까지 채우는 조회의 최대 월수. */
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

        List<DailyTotal> found = recordRepository.sumDailyTotals(connectionId, from, to);
        return fillMissingDates(from, days, found);
    }

    /**
     * @throws BusinessException 범위가 뒤집혔거나 상한을 넘을 때, 조회할 수 없는
     *     {@code recordkey}일 때
     */
    public List<MonthlyTotal> monthly(
            long memberId, String recordKey, YearMonth from, YearMonth to) {

        int months = requireValidRange(from, to);
        long connectionId = requireOwnedConnection(memberId, recordKey);

        List<MonthlyTotal> found =
                recordRepository.sumMonthlyTotals(
                        connectionId, from.atDay(1), to.atEndOfMonth());
        return fillMissingMonths(from, months, found);
    }

    private static int requireValidRange(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        // 시작일과 종료일을 모두 포함.
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
     * 조회 범위의 모든 날짜를 채우고 순서를 보장.
     * 데이터가 없는 날은 0.
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

    /** 조회 범위의 모든 월을 채우고 순서를 보장. 데이터가 없는 월은 0. */
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
