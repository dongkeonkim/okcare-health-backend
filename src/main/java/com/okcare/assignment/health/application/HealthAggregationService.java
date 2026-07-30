package com.okcare.assignment.health.application;

import com.okcare.assignment.common.error.BusinessException;
import com.okcare.assignment.common.error.ErrorCode;
import com.okcare.assignment.health.domain.DailyTotal;
import com.okcare.assignment.health.domain.HealthConnection;
import com.okcare.assignment.health.domain.MonthlyTotal;
import com.okcare.assignment.health.infrastructure.HealthActivityRecordRepository;
import com.okcare.assignment.health.infrastructure.HealthAggregationCache;
import com.okcare.assignment.health.infrastructure.HealthConnectionRepository;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * 집계 조회.
 *
 * <p>반올림하지 않은 합계까지만 만듦. 응답 정밀도로 줄이는 것은 응답 객체가 함. 여기서 줄이면 월간
 * 집계가 일별 반올림값을 더하는 길이 열리고, 그러면 기능 명세의 월간 기대값과 어긋남.
 *
 * <p>함정: 이 클래스에 {@code @Transactional}을 붙이지 말 것. 붙이면 소유권 조회가 첫 DB 읽기가
 * 되어 REPEATABLE READ 스냅샷이 그 시점에 고정되고, 그 뒤 읽는 Redis version은 더 새로울 수 있음.
 * 그러면 저장 전 값이 새 version 키에 실려 TTL 내내 후속 조회에도 나감. 저장 직후 조회하는 흐름에서
 * 실제로 걸리는 경로.
 *
 * <p>붙이지 않으면 집계 쿼리가 version을 읽은 뒤에 자기 스냅샷을 만듦. 스냅샷이 version보다
 * 새로워서 캐시에 담기는 값은 그 version이 약속한 것보다 새롭거나 같음. 낡은 값이 실릴 수 없음.
 *
 * <p>version을 먼저 읽도록 순서를 바꾸는 방법도 있지만 캐시가 version을 호출부에 노출해야 하고,
 * 그러면 호출부가 version을 두 번 읽는 원래 문제가 돌아옴.
 *
 * <p>두 쿼리가 스냅샷을 공유해야 할 이유도 없음. 소유권과 집계는 서로 독립이고, 각 리포지토리
 * 호출이 독립적으로 실행되어 같은 스냅샷을 물려받지 않음. {@code SimpleJpaRepository}가 상속한
 * CRUD 메서드에는 {@code readOnly} 트랜잭션이 붙지만 선언 쿼리 메서드에는 붙지 않으므로, 여기에
 * {@code @Transactional}이 없으면 두 쿼리 사이에 공유할 트랜잭션 자체가 없음.
 */
@Service
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
    private final HealthAggregationCache cache;

    public HealthAggregationService(
            HealthConnectionRepository connectionRepository,
            HealthActivityRecordRepository recordRepository,
            HealthAggregationCache cache) {
        this.connectionRepository = connectionRepository;
        this.recordRepository = recordRepository;
        this.cache = cache;
    }

    /**
     * @throws BusinessException 범위가 뒤집혔거나 상한을 넘을 때, 조회할 수 없는
     *     {@code recordkey}일 때
     */
    public List<DailyTotal> daily(long memberId, String recordKey, LocalDate from, LocalDate to) {
        int days = requireValidRange(from, to);
        long connectionId = requireOwnedConnection(memberId, recordKey);

        Supplier<List<DailyTotal>> loader =
                () -> {
                    List<DailyTotal> found =
                            recordRepository.sumDailyTotals(connectionId, from, to);

                    return fillMissingDates(from, days, found);
                };

        // 소유권 확인을 캐시보다 앞에 둠. 뒤에 두면 남의 recordkey로도 캐시된 값이 나감.
        return cache.daily(recordKey, from, to, loader);
    }

    /**
     * @throws BusinessException 범위가 뒤집혔거나 상한을 넘을 때, 조회할 수 없는
     *     {@code recordkey}일 때
     */
    public List<MonthlyTotal> monthly(
            long memberId, String recordKey, YearMonth from, YearMonth to) {

        int months = requireValidRange(from, to);
        long connectionId = requireOwnedConnection(memberId, recordKey);

        Supplier<List<MonthlyTotal>> loader =
                () -> {
                    // 시작 월의 1일부터 종료 월의 말일까지. 말일 계산을 YearMonth에 맡겨 2월과
                    // 윤년을 따로 다루지 않음.
                    List<MonthlyTotal> found =
                            recordRepository.sumMonthlyTotals(
                                    connectionId, from.atDay(1), to.atEndOfMonth());

                    return fillMissingMonths(from, months, found);
                };

        return cache.monthly(recordKey, from, to, loader);
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
