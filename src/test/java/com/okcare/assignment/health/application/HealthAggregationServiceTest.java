package com.okcare.assignment.health.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.okcare.assignment.common.error.BusinessException;
import com.okcare.assignment.common.error.ErrorCode;
import com.okcare.assignment.health.domain.DailyTotal;
import com.okcare.assignment.health.domain.HealthConnection;
import com.okcare.assignment.health.domain.MonthlyTotal;
import com.okcare.assignment.health.infrastructure.HealthActivityRecordRepository;
import com.okcare.assignment.health.infrastructure.HealthConnectionRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 조회 범위 상한이 없으면 요청이 응답 크기를 정하고, 소유권 조회가 새면 남의 건강 데이터가 나감. 둘
 * 다 조회 API에서 가장 비싼 실수라 리포지토리를 대역으로 두고 분기만 좁혀 확인.
 */
class HealthAggregationServiceTest {

    private static final long MEMBER_ID = 7L;
    private static final long CONNECTION_ID = 42L;
    private static final String RECORD_KEY = "7836887b-b12a-440f-af0f-851546504b13";
    private static final LocalDate FROM = LocalDate.of(2024, 11, 1);
    private static final YearMonth FROM_MONTH = YearMonth.of(2024, 11);

    private final HealthConnectionRepository connectionRepository =
            mock(HealthConnectionRepository.class);

    private final HealthActivityRecordRepository recordRepository =
            mock(HealthActivityRecordRepository.class);

    private final HealthAggregationService service =
            new HealthAggregationService(connectionRepository, recordRepository);

    @Test
    @DisplayName("범위가 뒤집히면 저장소를 조회하지 않고 거절한다")
    void rejectsReversedRangeBeforeTouchingRepositories() {
        assertBadRequest(() -> service.daily(MEMBER_ID, RECORD_KEY, FROM, FROM.minusDays(1)));

        // 검증을 소유권 조회 뒤에 두면 잘못된 요청이 매번 DB를 건드림.
        verifyNoInteractions(connectionRepository, recordRepository);
    }

    @Test
    @DisplayName("상한과 같은 366일은 조회하고 하루 넘으면 거절한다")
    void allowsExactlyMaxDaysAndRejectsOneMore() {
        givenOwnedConnection();
        givenNoStoredTotals();

        LocalDate lastAllowed = FROM.plusDays(HealthAggregationService.MAX_DAYS - 1);
        assertThat(service.daily(MEMBER_ID, RECORD_KEY, FROM, lastAllowed))
                .hasSize(HealthAggregationService.MAX_DAYS);

        assertBadRequest(() -> service.daily(MEMBER_ID, RECORD_KEY, FROM, lastAllowed.plusDays(1)));
    }

    @Test
    @DisplayName("같은 날짜를 조회하면 하루로 센다")
    void countsSingleDayRange() {
        givenOwnedConnection();
        givenNoStoredTotals();

        assertThat(service.daily(MEMBER_ID, RECORD_KEY, FROM, FROM)).hasSize(1);
    }

    @Test
    @DisplayName("회원이 소유하지 않은 recordkey는 없는 것과 같은 코드로 거절한다")
    void rejectsRecordKeyThatIsNotOwned() {
        given(connectionRepository.findByRecordKeyAndMemberId(RECORD_KEY, MEMBER_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.daily(MEMBER_ID, RECORD_KEY, FROM, FROM))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).errorCode())
                .isEqualTo(ErrorCode.HEALTH_RECORD_KEY_NOT_FOUND);
    }

    @Test
    @DisplayName("데이터가 없는 날짜를 0으로 채우고 오래된 날짜부터 정렬한다")
    void fillsMissingDatesInChronologicalOrder() {
        givenOwnedConnection();
        // 조회 쿼리에 order by를 걸지 않으므로 순서가 뒤섞인 결과로 정렬 책임을 확인.
        given(recordRepository.sumDailyTotals(any(), any(), any()))
                .willReturn(
                        List.of(
                                storedTotal(FROM.plusDays(2), "300"),
                                storedTotal(FROM, "100")));

        List<DailyTotal> totals = service.daily(MEMBER_ID, RECORD_KEY, FROM, FROM.plusDays(3));

        assertThat(totals)
                .extracting(DailyTotal::date)
                .containsExactly(FROM, FROM.plusDays(1), FROM.plusDays(2), FROM.plusDays(3));
        assertThat(totals)
                .extracting(total -> total.steps().stripTrailingZeros().toPlainString())
                .containsExactly("100", "0", "300", "0");
    }

    @Test
    @DisplayName("저장소가 준 합계를 반올림하지 않고 그대로 넘긴다")
    void neverRoundsBeforeReturning() {
        givenOwnedConnection();
        BigDecimal steps = new BigDecimal("7243.4999999999");
        BigDecimal calories = new BigDecimal("289.2099515123");
        BigDecimal distance = new BigDecimal("5.4194904567");
        given(recordRepository.sumDailyTotals(any(), any(), any()))
                .willReturn(List.of(new DailyTotal(FROM, steps, calories, distance)));

        DailyTotal returned = service.daily(MEMBER_ID, RECORD_KEY, FROM, FROM).get(0);

        // 이 계층에서 반올림하면 월간 합산 오차 발생.
        // 반올림 전 scale 유지.
        assertThat(returned.steps()).isEqualTo(steps);
        assertThat(returned.calories()).isEqualTo(calories);
        assertThat(returned.distance()).isEqualTo(distance);
    }

    @Test
    @DisplayName("월간도 상한과 같은 24개월은 조회하고 한 달 넘으면 거절한다")
    void allowsExactlyMaxMonthsAndRejectsOneMore() {
        givenOwnedConnection();
        given(recordRepository.sumMonthlyTotals(any(), any(), any())).willReturn(List.of());

        YearMonth lastAllowed = FROM_MONTH.plusMonths(HealthAggregationService.MAX_MONTHS - 1);
        assertThat(service.monthly(MEMBER_ID, RECORD_KEY, FROM_MONTH, lastAllowed))
                .hasSize(HealthAggregationService.MAX_MONTHS);

        assertBadRequest(
                () ->
                        service.monthly(
                                MEMBER_ID, RECORD_KEY, FROM_MONTH, lastAllowed.plusMonths(1)));
        assertBadRequest(
                () ->
                        service.monthly(
                                MEMBER_ID, RECORD_KEY, FROM_MONTH, FROM_MONTH.minusMonths(1)));
    }

    @Test
    @DisplayName("월 범위를 시작 월 1일부터 종료 월 말일까지로 바꿔 조회한다")
    void convertsMonthRangeToInclusiveDateRange() {
        givenOwnedConnection();
        given(recordRepository.sumMonthlyTotals(any(), any(), any())).willReturn(List.of());

        // 윤년 2월. 말일을 직접 계산하면 28일로 잘못 잘라 2월 29일 데이터가 집계에서 빠짐.
        service.monthly(MEMBER_ID, RECORD_KEY, YearMonth.of(2024, 1), YearMonth.of(2024, 2));

        verify(recordRepository)
                .sumMonthlyTotals(
                        CONNECTION_ID, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 2, 29));
    }

    @Test
    @DisplayName("데이터가 없는 월을 0으로 채우고 오래된 월부터 정렬한다")
    void fillsMissingMonthsInChronologicalOrder() {
        givenOwnedConnection();
        given(recordRepository.sumMonthlyTotals(any(), any(), any()))
                .willReturn(
                        List.of(
                                monthlyTotal(FROM_MONTH.plusMonths(2), "300"),
                                monthlyTotal(FROM_MONTH, "100")));

        List<MonthlyTotal> totals =
                service.monthly(MEMBER_ID, RECORD_KEY, FROM_MONTH, FROM_MONTH.plusMonths(3));

        assertThat(totals)
                .extracting(MonthlyTotal::month)
                .containsExactly(
                        FROM_MONTH,
                        FROM_MONTH.plusMonths(1),
                        FROM_MONTH.plusMonths(2),
                        FROM_MONTH.plusMonths(3));
        assertThat(totals)
                .extracting(total -> total.steps().stripTrailingZeros().toPlainString())
                .containsExactly("100", "0", "300", "0");
    }

    @Test
    @DisplayName("월간도 저장소가 준 합계를 반올림하지 않고 그대로 넘긴다")
    void neverRoundsMonthlyBeforeReturning() {
        givenOwnedConnection();
        BigDecimal steps = new BigDecimal("124783.4999999999");
        BigDecimal calories = new BigDecimal("5002.4994391234");
        // 세 값을 서로 다르게 둠. 같은 값을 쓰면 한 측정값만 조기 반올림해도 단언이 통과함.
        BigDecimal distance = new BigDecimal("94.3420951234");
        given(recordRepository.sumMonthlyTotals(any(), any(), any()))
                .willReturn(List.of(new MonthlyTotal(FROM_MONTH, steps, calories, distance)));

        MonthlyTotal returned =
                service.monthly(MEMBER_ID, RECORD_KEY, FROM_MONTH, FROM_MONTH).get(0);

        assertThat(returned.steps()).isEqualTo(steps);
        assertThat(returned.calories()).isEqualTo(calories);
        assertThat(returned.distance()).isEqualTo(distance);
    }

    @Test
    @DisplayName("월간 조회도 소유하지 않은 recordkey를 404 코드로 거절한다")
    void rejectsMonthlyForRecordKeyThatIsNotOwned() {
        given(connectionRepository.findByRecordKeyAndMemberId(RECORD_KEY, MEMBER_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(
                        () -> service.monthly(MEMBER_ID, RECORD_KEY, FROM_MONTH, FROM_MONTH))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).errorCode())
                .isEqualTo(ErrorCode.HEALTH_RECORD_KEY_NOT_FOUND);
    }

    private static MonthlyTotal monthlyTotal(YearMonth month, String steps) {
        return new MonthlyTotal(month, new BigDecimal(steps), BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private void givenOwnedConnection() {
        HealthConnection connection = mock(HealthConnection.class);
        ReflectionTestUtils.setField(connection, "id", CONNECTION_ID);
        given(connection.getId()).willReturn(CONNECTION_ID);
        given(connectionRepository.findByRecordKeyAndMemberId(RECORD_KEY, MEMBER_ID))
                .willReturn(Optional.of(connection));
    }

    private void givenNoStoredTotals() {
        given(recordRepository.sumDailyTotals(any(), any(), any())).willReturn(List.of());
    }

    private static DailyTotal storedTotal(LocalDate date, String steps) {
        return new DailyTotal(
                date, new BigDecimal(steps), BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private static void assertBadRequest(ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).errorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }
}
