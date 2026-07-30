package com.okcare.assignment.health.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.okcare.assignment.health.domain.DailyTotal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

/**
 * 반올림 결과를 {@code BigDecimal}로 비교하면 {@code 0E-6}도 {@code 0.000000}과 같다고 판정되지만,
 * 직렬화하면 {@code 0E-6}이 그대로 나가 명세의 응답 예시와 어긋남. 그래서 값이 아니라 JSON 문자열을
 * 확인.
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
    @DisplayName("걸음수는 정수로, 칼로리와 거리는 소수점 여섯 자리로 직렬화한다")
    void serializesRoundedValues() throws Exception {
        String json = write(total("7243.4999", "289.2099515", "5.4194904"));

        assertThat(json)
                .contains("\"recordKey\":\"" + RECORD_KEY + "\"")
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

        // 지수 표기나 0으로 줄면 명세의 응답 예시와 다른 형식이 됨. 칼로리가 전부 0인 recordkey가
        // fixture에 실제로 있어 항상 지나가는 경로.
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

    private String write(DailyTotal total) throws Exception {
        return objectMapper.writeValueAsString(
                DailyAggregationResponse.of(RECORD_KEY, SEOUL, List.of(total)));
    }

    private static DailyTotal total(String steps, String calories, String distance) {
        return new DailyTotal(
                DATE, new BigDecimal(steps), new BigDecimal(calories), new BigDecimal(distance));
    }
}
