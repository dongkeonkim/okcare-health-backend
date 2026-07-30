package com.okcare.assignment.health.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.okcare.assignment.common.error.BusinessException;
import com.okcare.assignment.common.error.ErrorCode;
import com.okcare.assignment.health.domain.DailyTotal;
import com.okcare.assignment.health.domain.HealthConnection;
import com.okcare.assignment.health.infrastructure.HealthActivityRecordRepository;
import com.okcare.assignment.health.infrastructure.HealthConnectionRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
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

        // 경계를 한쪽만 확인하면 상한이 off-by-one이어도 통과함.
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

        // 이 계층이 응답 정밀도로 미리 줄이면 월간 집계가 일별 반올림값을 더하게 되고 기능 명세의
        // 월간 기대값과 어긋남. 그런데 응답은 어차피 같은 자리에서 다시 반올림하므로 API 결과가
        // 바뀌지 않아 다른 어느 테스트도 잡지 못함. 계층 계약을 여기서 직접 고정.
        // BigDecimal의 isEqualTo는 scale까지 비교하므로 자리수가 줄어든 것도 걸림.
        assertThat(returned.steps()).isEqualTo(steps);
        assertThat(returned.calories()).isEqualTo(calories);
        assertThat(returned.distance()).isEqualTo(distance);
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
