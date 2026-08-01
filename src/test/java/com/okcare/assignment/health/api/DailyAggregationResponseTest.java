package com.okcare.assignment.health.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.okcare.assignment.health.domain.DailyTotal;
import com.okcare.assignment.health.domain.MonthlyTotal;
import com.okcare.assignment.health.domain.SaveResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

/**
 * {@code BigDecimal} 비교만으로 JSON 소수 자릿수 검증 불가.
 * 응답 문자열로 확인.
 *
 * <p>{@code new ObjectMapper()}가 아니라 실제 애플리케이션의 Jackson 구성을 씀. 직접 만든 것은
 * 날짜를 숫자로 직렬화해 여기서만 통과하는 결과가 나옴.
 */
@JsonTest
class DailyAggregationResponseTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final String RECORD_KEY = "7836887b-b12a-440f-af0f-851546504b13";
    private static final LocalDate DATE = LocalDate.of(2024, 11, 15);

    @Autowired private ObjectMapper objectMapper;

    @Test
    @DisplayName("응답 record는 recordKey 접근자를 유지한다")
    void keepsCamelCaseRecordKeyAccessor() {
        assertThat(HealthDataSaveResponse.from(RECORD_KEY, new SaveResult(1, 1, 0, 0)).recordKey())
                .isEqualTo(RECORD_KEY);
        assertThat(DailyAggregationResponse.of(RECORD_KEY, SEOUL, List.of()).recordKey())
                .isEqualTo(RECORD_KEY);
        assertThat(MonthlyAggregationResponse.of(RECORD_KEY, SEOUL, List.of()).recordKey())
                .isEqualTo(RECORD_KEY);
    }

    @Test
    @DisplayName("걸음수는 정수로, 칼로리와 거리는 소수점 여섯 자리로 직렬화한다")
    void serializesRoundedValues() throws Exception {
        String json = write(total("7243.4999", "289.2099515", "5.4194904"));

        assertThat(json)
                .contains("\"recordkey\":\"" + RECORD_KEY + "\"")
                .doesNotContain("\"recordKey\"")
                .contains("\"zoneId\":\"Asia/Seoul\"")
                .contains("\"date\":\"2024-11-15\"")
                .contains("\"steps\":7243")
                .contains("\"calories\":289.209952")
                .contains("\"distance\":5.419490");
    }

    @Test
    @DisplayName("값이 0인 날도 소수점 여섯 자리를 유지한다")
    void keepsScaleForZeroValues() throws Exception {
        String json = write(DailyTotal.empty(DATE));

        // 지수 표기나 0으로 축약되지 않는지 확인.
        assertThat(json).contains("\"steps\":0").contains("\"calories\":0.000000");
        assertThat(json).contains("\"distance\":0.000000").doesNotContain("E-");
    }

    @ParameterizedTest
    @CsvSource({
        // 걸음수 반올림이 HALF_UP인지. 저장값이 소수라 하루 합계도 소수로 끝남.
        "0.5, 1",
        "0.4999999, 0",
        "1.5, 2",
        "2.5, 3"
    })
    @DisplayName("걸음수를 HALF_UP으로 정수 반올림한다")
    void roundsStepsHalfUp(String stored, long expected) throws Exception {
        assertThat(write(total(stored, "0", "0"))).contains("\"steps\":" + expected);
    }

    @ParameterizedTest
    @CsvSource({
        "0.0000005, 0.000001",
        "0.0000004, 0.000000",
        "0.1234565, 0.123457"
    })
    @DisplayName("칼로리를 소수점 여섯 자리에서 HALF_UP으로 반올림한다")
    void roundsCaloriesHalfUp(String stored, String expected) throws Exception {
        assertThat(write(total("0", stored, "0"))).contains("\"calories\":" + expected);
    }

    @Test
    @DisplayName("월간 응답은 month를 yyyy-MM 문자열로 직렬화한다")
    void serializesMonthAsPattern() throws Exception {
        // YearMonth가 배열이 아닌 yyyy-MM 문자열로 직렬화되는지 확인.
        String json =
                objectMapper.writeValueAsString(
                        MonthlyAggregationResponse.of(
                                RECORD_KEY,
                                SEOUL,
                                List.of(
                                        new MonthlyTotal(
                                                YearMonth.of(2024, 11),
                                                new BigDecimal("124783.4999"),
                                                new BigDecimal("5002.4994391"),
                                                new BigDecimal("94.3420945")))));

        assertThat(json)
                .contains("\"month\":\"2024-11\"")
                .contains("\"steps\":124783")
                .contains("\"calories\":5002.499439")
                .contains("\"distance\":94.342095")
                .doesNotContain("[2024");
    }

    @Test
    @DisplayName("월간 응답도 값이 0인 달의 소수점 여섯 자리를 유지한다")
    void keepsMonthlyScaleForZeroValues() throws Exception {
        String json =
                objectMapper.writeValueAsString(
                        MonthlyAggregationResponse.of(
                                RECORD_KEY,
                                SEOUL,
                                List.of(MonthlyTotal.empty(YearMonth.of(2024, 10)))));

        assertThat(json).contains("\"calories\":0.000000").contains("\"distance\":0.000000");
        assertThat(json).doesNotContain("E-");
    }

    private String write(DailyTotal total) throws Exception {
        return objectMapper.writeValueAsString(
                DailyAggregationResponse.of(RECORD_KEY, SEOUL, List.of(total)));
    }

    private static DailyTotal total(String steps, String calories, String distance) {
        return new DailyTotal(
                DATE, new BigDecimal(steps), new BigDecimal(calories), new BigDecimal(distance));
    }
}
