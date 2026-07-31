package com.okcare.assignment.health.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.okcare.assignment.common.error.BusinessException;
import com.okcare.assignment.common.error.ErrorCode;
import com.okcare.assignment.config.AppProperties;
import com.okcare.assignment.health.api.HealthDataRequest;
import com.okcare.assignment.health.domain.NormalizedPayload;
import com.okcare.assignment.health.domain.NormalizedRecord;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 공급자별 시각 해석이 어긋나면 집계 날짜가 하루씩 밀려 회귀값 전체가 틀어짐. 저장 정밀도와 해시
 * 입력이 어긋나면 재전송 판정이 거짓이 됨. 둘 다 저장 뒤에는 원인을 찾기 어려운 자리.
 */
class HealthDataNormalizerTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private static final String SAMSUNG_FROM = "2024-11-15 00:00:00";
    private static final String SAMSUNG_TO = "2024-11-15 00:10:00";
    private static final String HEALTH_KIT_FROM = "2024-11-14T21:20:00+0000";
    private static final String HEALTH_KIT_TO = "2024-11-14T21:30:00+0000";
    private static final String LAST_UPDATE = "2024-12-16 14:40:00 +0000";
    private static final String RECORD_KEY = "7836887b-b12a-440f-af0f-851546504b13";

    private final HealthDataNormalizer normalizer =
            new HealthDataNormalizer(new AppProperties(SEOUL));

    @Test
    @DisplayName("타임존이 없는 삼성헬스 시각을 사업 기준 타임존으로 해석한다")
    void interpretsSamsungHealthTimeInBusinessZone() {
        NormalizedRecord record = onlyRecord(samsung(SAMSUNG_FROM, SAMSUNG_TO));

        // 2024-11-15 00:00 KST == 2024-11-14 15:00 UTC. UTC로 해석하면 집계 날짜가 하루 밀림.
        assertThat(record.periodStart()).isEqualTo(Instant.parse("2024-11-14T15:00:00Z"));
        assertThat(record.periodEnd()).isEqualTo(Instant.parse("2024-11-14T15:10:00Z"));
        assertThat(record.activityDate()).isEqualTo(LocalDate.of(2024, 11, 15));
    }

    @Test
    @DisplayName("Apple Health의 오프셋 시각은 요청에 담긴 오프셋을 적용한다")
    void appliesHealthKitOffset() {
        NormalizedRecord record = onlyRecord(healthKit(HEALTH_KIT_FROM, HEALTH_KIT_TO));

        assertThat(record.periodStart()).isEqualTo(Instant.parse("2024-11-14T21:20:00Z"));
        // 오프셋 시각을 사업 기준 타임존으로 옮기면 날짜가 넘어감. fixture 첫 엔트리의 실제 사례.
        assertThat(record.activityDate()).isEqualTo(LocalDate.of(2024, 11, 15));
    }

    @Test
    @DisplayName("시작과 종료가 같은 구간을 버리지 않고 시작 시각의 날짜에 넣는다")
    void keepsZeroLengthPeriod() {
        NormalizedRecord record = onlyRecord(samsung(SAMSUNG_FROM, SAMSUNG_FROM));

        assertThat(record.periodStart()).isEqualTo(record.periodEnd());
        assertThat(record.activityDate()).isEqualTo(LocalDate.of(2024, 11, 15));
    }

    @Test
    @DisplayName("공급자가 알린 갱신 시각을 UTC로 정규화한다")
    void normalizesLastUpdate() {
        NormalizedPayload payload = normalizer.normalize(samsung(SAMSUNG_FROM, SAMSUNG_TO));

        assertThat(payload.sourceLastUpdatedAt()).isEqualTo(Instant.parse("2024-12-16T14:40:00Z"));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @DisplayName("측정값을 저장 정밀도인 소수점 12자리로 반올림한다")
    @CsvSource({
        "24, 24.000000000000",
        "287.6726411513615, 287.672641151362",
        "0.07921212796269472, 0.079212127963"
    })
    void quantizesToStoredScale(String raw, String expected) {
        // MySQL의 암묵적 반올림에 맡기면 저장값과 해시 대상이 어긋나, 12자리 뒤만 다른 재전송이
        // 행은 그대로인데 updated로 세어짐. 여기서 맞추는 값이 MySQL 결과와 같아야 함.
        NormalizedRecord record = onlyRecord(withSteps(new BigDecimal(raw)));

        assertThat(record.steps().toPlainString()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("저장 범위를 넘는 측정값을 거부한다")
    @CsvSource({
        "정수부 13자리, 1000000000000",
        "반올림 carry로 13자리, 999999999999.9999999999995",
        "음수, -1000000000000"
    })
    void rejectsValueOutOfStoredRange(String label, String steps) {
        // DECIMAL(24,12)의 정수부는 12자리. 넘으면 MySQL이 범위 초과로 insert를 실패시켜
        // 400이어야 할 것이 500이 됨. carry 사례는 양자화 전에는 12자리라 사전 검사만으로는
        // 통과함.
        assertThatRejects(() -> normalizer.normalize(withSteps(new BigDecimal(steps))));
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("scale이 극단적으로 큰 지수 표기를 400으로 거부한다")
    @CsvSource({"9자리 지수, 1E-999999999", "int 상한 지수, 1E-2147483647"})
    void rejectsExtremeExponent(String label, String steps) {
        // 절댓값은 작아 범위 검사를 통과하지만 setScale이 내부 BigInteger 한계를 넘어 실패함.
        // 잡지 않으면 요청 검증 오류가 500으로 드러남.
        assertThatRejects(() -> normalizer.normalize(withSteps(new BigDecimal(steps))));
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("저장 범위 안의 경계값은 받는다")
    @CsvSource({
        "정수부 12자리, 999999999999, 999999999999.000000000000",
        "carry 직전, 999999999999.9999999999994, 999999999999.999999999999",
        "음수 scale의 0, 0E+13, 0.000000000000",
        "음수 scale의 정상값, 9.99E+11, 999000000000.000000000000"
    })
    void acceptsBoundaryValues(String label, String steps, String expected) {
        // 자릿수를 precision() - scale()로 세면 0E+13처럼 저장 가능한 값이 거절됨. 0의 precision은
        // 항상 1이라 음수 scale에서 없는 정수부가 생김.
        NormalizedRecord record = onlyRecord(withSteps(new BigDecimal(steps)));

        assertThat(record.steps().toPlainString()).isEqualTo(expected);
    }

    @Test
    @DisplayName("calories 0을 유효한 값으로 보존한다")
    void preservesZeroCalories() {
        NormalizedRecord record = onlyRecord(healthKit(HEALTH_KIT_FROM, HEALTH_KIT_TO));

        assertThat(record.calories()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("세 측정값 중 어느 것이 바뀌어도 해시가 바뀐다")
    @CsvSource({"steps", "calories", "distance"})
    void hashCoversEveryMeasurement(String changed) {
        // 해시 입력에서 한 값을 빠뜨리면 그 값의 변경이 duplicated로 오판되어 갱신을 잃음.
        String before = hashOf("1", "2", "3");
        String after =
                switch (changed) {
                    case "steps" -> hashOf("9", "2", "3");
                    case "calories" -> hashOf("1", "9", "3");
                    default -> hashOf("1", "2", "9");
                };

        assertThat(before).isNotEqualTo(after);
    }

    @Test
    @DisplayName("측정값이 같고 갱신 시각만 다르면 같은 해시를 만든다")
    void hashIgnoresLastUpdate() {
        // 갱신 시각을 해시에 넣으면 값이 같은 재전송이 updated로 오판됨.
        String first = onlyRecord(withLastUpdate("2024-12-16 14:40:00 +0000")).payloadHash();
        String second = onlyRecord(withLastUpdate("2024-12-17 09:00:00 +0000")).payloadHash();

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("측정값이 같으면 표기가 달라도 같은 해시를 만든다")
    void hashIgnoresScaleDifference() {
        // 54와 54.0은 같은 값. 표기 차이가 해시를 바꾸면 공급자가 표기만 손봐도 변경으로 판정됨.
        assertThat(hashOf("54", "2", "3")).isEqualTo(hashOf("54.0", "2.00", "3.000"));
    }

    @Test
    @DisplayName("12자리 뒤만 다른 두 요청은 같은 해시를 만든다")
    void hashIgnoresDifferenceBeyondStoredScale() {
        // 저장하면 같은 행이 되므로 updated가 아니라 duplicated여야 함. 원본에서 해시를 계산하면
        // 여기서 갈라져 카운트가 거짓이 됨.
        assertThat(hashOf("0.0792121279630001", "2", "3"))
                .isEqualTo(hashOf("0.0792121279630002", "2", "3"));
    }

    @Test
    @DisplayName("지원하지 않는 공급자를 거부한다")
    void rejectsUnknownProvider() {
        // 값의 모양으로 형식을 추론하면 검증하지 않은 공급자의 시각 의미를 잘못 해석하게 됨.
        assertThatRejects(
                () ->
                        normalizer.normalize(
                                request("Garmin", SAMSUNG_FROM, SAMSUNG_TO, LAST_UPDATE,
                                        "54", "0", "0.04223", "kcal", "km")));
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("지원하지 않는 단위를 거부한다")
    @CsvSource({"칼로리 cal, cal, km", "거리 m, kcal, m", "거리 mi, kcal, mi"})
    void rejectsUnsupportedUnit(String label, String caloriesUnit, String distanceUnit) {
        assertThatRejects(
                () ->
                        normalizer.normalize(
                                request("SamsungHealth", SAMSUNG_FROM, SAMSUNG_TO, LAST_UPDATE,
                                        "54", "0", "0.04223", caloriesUnit, distanceUnit)));
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("공급자의 형식과 다른 시각을 거부한다")
    @CsvSource({
        "삼성헬스에 오프셋, SamsungHealth, 2024-11-15T00:00:00+0900",
        "Apple Health에 오프셋 없음, Health Kit, 2024-11-15 00:00:00",
        "형식이 아예 다름, SamsungHealth, 2024/11/15"
    })
    void rejectsForeignTimeFormat(String label, String sourceName, String from) {
        // 공급자가 계약을 바꾸면 조용히 추측하지 않고 크게 실패해야 함.
        assertThatRejects(
                () ->
                        normalizer.normalize(
                                request(sourceName, from, from, LAST_UPDATE,
                                        "54", "0", "0.04223", "kcal", "km")));
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("달력에 없는 날짜와 시각을 보정하지 않고 거부한다")
    @CsvSource({
        "삼성헬스 2월 30일, SamsungHealth, 2024-02-30 00:00:00",
        "삼성헬스 24시, SamsungHealth, 2024-11-15 24:00:00",
        "Apple Health 2월 30일, Health Kit, 2024-02-30T00:00:00+0000",
        "Apple Health 24시, Health Kit, 2024-11-15T24:00:00+0000"
    })
    void rejectsNonExistentCalendarTime(String label, String sourceName, String from) {
        // 기본 ResolverStyle.SMART는 2024-02-30을 2024-02-29로, 24:00:00을 다음 날 자정으로
        // 조용히 보정함. 400으로 거절해야 할 입력이 다른 식별자로 저장되어 집계까지 오염됨.
        assertThatRejects(
                () ->
                        normalizer.normalize(
                                request(sourceName, from, from, LAST_UPDATE,
                                        "54", "0", "0.04223", "kcal", "km")));
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("파싱할 수 없거나 달력에 없는 갱신 시각을 거부한다")
    @CsvSource({
        "오프셋 앞 공백 없음, 2024-12-16T14:40:00Z",
        "달력에 없는 날짜, 2024-02-30 00:00:00 +0000"
    })
    void rejectsInvalidLastUpdate(String label, String lastUpdate) {
        assertThatRejects(
                () ->
                        normalizer.normalize(
                                request("SamsungHealth", SAMSUNG_FROM, SAMSUNG_TO, lastUpdate,
                                        "54", "0", "0.04223", "kcal", "km")));
    }

    @Test
    @DisplayName("오류에 원본 시각 문자열을 담지 않는다")
    void neverEchoesPayloadInError() {
        // 오류 응답과 로그로 요청 payload가 새어 나가면 안 됨.
        String secretLooking = "2024/11/15 00:00:00";

        assertThatThrownBy(
                        () ->
                                normalizer.normalize(
                                        request("SamsungHealth", secretLooking, secretLooking,
                                                LAST_UPDATE, "54", "0", "0.04223", "kcal", "km")))
                .hasMessageNotContaining(secretLooking);
    }

    private String hashOf(String steps, String calories, String distance) {
        return onlyRecord(
                        request("SamsungHealth", SAMSUNG_FROM, SAMSUNG_TO, LAST_UPDATE,
                                steps, calories, distance, "kcal", "km"))
                .payloadHash();
    }

    private NormalizedRecord onlyRecord(HealthDataRequest request) {
        List<NormalizedRecord> records = normalizer.normalize(request).records();

        assertThat(records).hasSize(1);
        return records.get(0);
    }

    private static void assertThatRejects(ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.HEALTH_DATA_INVALID);
    }

    private static HealthDataRequest samsung(String from, String to) {
        return request("SamsungHealth", from, to, LAST_UPDATE, "54", "0", "0.04223", "kcal", "km");
    }

    private static HealthDataRequest healthKit(String from, String to) {
        return request("Health Kit", from, to, LAST_UPDATE, "24", "0", "0.0192", "kcal", "km");
    }

    /**
     * 극단적인 {@code BigDecimal} scale을 변환 없이 전달해 범위 검증 경계 확인.
     */
    private static HealthDataRequest withSteps(BigDecimal steps) {
        return new HealthDataRequest(
                RECORD_KEY,
                "steps",
                LAST_UPDATE,
                new HealthDataRequest.Data(
                        healthKitSource(),
                        List.of(
                                new HealthDataRequest.Entry(
                                        new HealthDataRequest.Period(
                                                HEALTH_KIT_FROM, HEALTH_KIT_TO),
                                        steps,
                                        new HealthDataRequest.Measure(BigDecimal.ZERO, "kcal"),
                                        new HealthDataRequest.Measure(
                                                new BigDecimal("0.0192"), "km")))));
    }

    private static HealthDataRequest.Source healthKitSource() {
        return new HealthDataRequest.Source(
                "Health Kit", 10, new HealthDataRequest.Product("iPhone", "Apple inc."));
    }

    private static HealthDataRequest withLastUpdate(String lastUpdate) {
        return request("SamsungHealth", SAMSUNG_FROM, SAMSUNG_TO, lastUpdate,
                "54", "0", "0.04223", "kcal", "km");
    }

    private static HealthDataRequest request(
            String sourceName,
            String from,
            String to,
            String lastUpdate,
            String steps,
            String calories,
            String distance,
            String caloriesUnit,
            String distanceUnit) {

        return new HealthDataRequest(
                RECORD_KEY,
                "steps",
                lastUpdate,
                new HealthDataRequest.Data(
                        new HealthDataRequest.Source(
                                sourceName, 9, new HealthDataRequest.Product("Android", "Samsung")),
                        List.of(
                                new HealthDataRequest.Entry(
                                        new HealthDataRequest.Period(from, to),
                                        new BigDecimal(steps),
                                        new HealthDataRequest.Measure(
                                                new BigDecimal(calories), caloriesUnit),
                                        new HealthDataRequest.Measure(
                                                new BigDecimal(distance), distanceUnit)))));
    }
}
