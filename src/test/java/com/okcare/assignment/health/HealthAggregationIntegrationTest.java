package com.okcare.assignment.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.okcare.assignment.RegressionBaseline;
import com.okcare.assignment.health.domain.DailyTotal;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 저장한 fixture를 실제 MySQL에서 집계하고 월별 회귀값과 대조.
 * 날짜·타임존 경계도 함께 검증.
 */
class HealthAggregationIntegrationTest extends HealthIntegrationSupport {

    private static final LocalDate RANGE_FROM = LocalDate.of(2024, 11, 1);
    private static final LocalDate RANGE_TO = LocalDate.of(2024, 12, 31);
    private static final LocalDate FIRST_STORED_DATE = LocalDate.of(2024, 11, 15);
    private static final int OUTPUT_SCALE = 6;
    private static final YearMonth MONTH_FROM = YearMonth.of(2024, 11);
    private static final YearMonth MONTH_TO = YearMonth.of(2024, 12);

    @Autowired private EntityManager entityManager;

    @Test
    @DisplayName("조회 범위의 모든 날짜를 오래된 날짜부터 반환한다")
    void returnsEveryDateInRange() throws Exception {
        String accessToken = storeAllFixtures("aggregate-range@example.com");
        String recordkey = firstRecordKey();

        JsonNode response =
                readTree(bodyOf(daily(accessToken, recordkey, RANGE_FROM, RANGE_TO)));
        JsonNode items = response.get("items");

        // 항목 수와 양 끝 날짜를 함께 단언. 채우기 누락과 off-by-one이 한 번에 걸림.
        assertThat(response.get("recordkey").asText()).isEqualTo(recordkey);
        assertThat(response.has("recordKey")).isFalse();
        assertThat(items).hasSize(61);
        assertThat(items.get(0).get("date").asText()).isEqualTo("2024-11-01");
        assertThat(items.get(60).get("date").asText()).isEqualTo("2024-12-31");
    }

    @Test
    @DisplayName("데이터가 없는 날짜는 0으로 채우고 소수점 여섯 자리를 유지한다")
    void fillsMissingDatesWithZero() throws Exception {
        String accessToken = storeAllFixtures("aggregate-fill@example.com");

        String body = bodyOf(daily(accessToken, firstRecordKey(), RANGE_FROM, RANGE_TO));

        // fixture가 11월 15일부터 시작하므로 앞 14일은 전부 채워진 값.
        for (LocalDate date = RANGE_FROM;
                date.isBefore(FIRST_STORED_DATE);
                date = date.plusDays(1)) {
            assertThat(body).contains(expectedItemJson(DailyTotal.empty(date)));
        }

        JsonNode items = readTree(body).get("items");
        assertThat(items.get(14).get("date").asText()).isEqualTo(FIRST_STORED_DATE.toString());
        assertThat(items.get(14).get("steps").asInt()).isPositive();
    }

    @Test
    @DisplayName("반올림 전 일별 합계를 월별로 더하면 회귀 기준의 월간 값과 일치한다")
    void dailyTotalsRollUpToMonthlyBaseline() throws Exception {
        storeAllFixtures("aggregate-rollup@example.com");
        Map<RegressionBaseline.MonthlyKey, RegressionBaseline.MonthlyTotal> baseline =
                RegressionBaseline.load().monthlyTotals();

        for (Map.Entry<RegressionBaseline.MonthlyKey, RegressionBaseline.MonthlyTotal> expected :
                baseline.entrySet()) {

            RegressionBaseline.MonthlyKey key = expected.getKey();
            List<DailyTotal> daysInMonth = storedDailyTotals(key.recordKey(), key.month());

            assertThat(roundedSteps(sum(daysInMonth, DailyTotal::steps)))
                    .as("%s %s 걸음수", key.recordKey(), key.month())
                    .isEqualTo(expected.getValue().steps());
            assertThat(roundedOutput(sum(daysInMonth, DailyTotal::calories)))
                    .as("%s %s 칼로리", key.recordKey(), key.month())
                    .isEqualTo(expected.getValue().calories().setScale(OUTPUT_SCALE));
            assertThat(roundedOutput(sum(daysInMonth, DailyTotal::distance)))
                    .as("%s %s 거리", key.recordKey(), key.month())
                    .isEqualTo(expected.getValue().distance().setScale(OUTPUT_SCALE));
        }
    }

    @Test
    @DisplayName("일별로 반올림한 값을 더하면 회귀 기준과 어긋난다")
    void roundingBeforeRollUpBreaksBaseline() throws Exception {
        storeAllFixtures("aggregate-rounding@example.com");
        Map<RegressionBaseline.MonthlyKey, RegressionBaseline.MonthlyTotal> baseline =
                RegressionBaseline.load().monthlyTotals();

        List<String> mismatched = new ArrayList<>();
        for (Map.Entry<RegressionBaseline.MonthlyKey, RegressionBaseline.MonthlyTotal> expected :
                baseline.entrySet()) {

            RegressionBaseline.MonthlyKey key = expected.getKey();
            List<DailyTotal> daysInMonth = storedDailyTotals(key.recordKey(), key.month());

            long steps =
                    daysInMonth.stream().mapToLong(day -> roundedSteps(day.steps())).sum();
            BigDecimal calories =
                    daysInMonth.stream()
                            .map(day -> roundedOutput(day.calories()))
                            .reduce(BigDecimal.ZERO, BigDecimal::add);

            if (steps != expected.getValue().steps()
                    || calories.compareTo(expected.getValue().calories()) != 0) {
                mismatched.add(key.recordKey() + " " + key.month());
            }
        }

        // 월간 집계를 일간 응답에서 굴려 올리면 안 되는 이유를 코드로 고정. 어긋나는 폭이 걸음
        // ±1과 소수 여섯째 자리라 눈으로 걸러지지 않고 테스트만 남는 방어 수단.
        assertThat(mismatched).isNotEmpty();
    }

    @Test
    @DisplayName("응답 값은 저장된 반올림 전 합계를 반올림한 결과와 같다")
    void responseRoundsStoredTotals() throws Exception {
        String accessToken = storeAllFixtures("aggregate-round-trip@example.com");
        String recordKey = firstRecordKey();

        List<DailyTotal> stored = storedDailyTotals(recordKey, RANGE_FROM, RANGE_TO);
        assertThat(stored).isNotEmpty();

        String body = bodyOf(daily(accessToken, recordKey, RANGE_FROM, RANGE_TO)
                .andExpect(status().isOk()));
        for (DailyTotal total : stored) {
            assertThat(body).contains(expectedItemJson(total));
        }
    }

    @Test
    @DisplayName("없는 recordkey와 남의 recordkey를 구분할 수 없는 404로 거절한다")
    void hidesRecordKeyExistence() throws Exception {
        String owner = storeAllFixtures("aggregate-owner@example.com");
        String stranger = accessTokenOf("aggregate-stranger@example.com");
        String ownedRecordKey = firstRecordKey();

        String unknownBody =
                failureBodyWithoutTrace(
                        daily(stranger, "00000000-0000-0000-0000-000000000000")
                                .andExpect(status().isNotFound()));
        String otherOwnerBody =
                failureBodyWithoutTrace(
                        daily(stranger, ownedRecordKey).andExpect(status().isNotFound()));

        // 본문까지 같아야 recordkey가 이미 쓰이는 중인지 확인하는 수단이 되지 않음.
        assertThat(otherOwnerBody).isEqualTo(unknownBody);

        // 소유자에게는 여전히 보임. 감추기가 과해져 정상 조회를 막는 것도 결함.
        daily(owner, ownedRecordKey).andExpect(status().isOk());
    }

    @Test
    @DisplayName("범위가 상한을 넘거나 뒤집히면 400을 반환한다")
    void rejectsInvalidRange() throws Exception {
        String accessToken = storeAllFixtures("aggregate-invalid-range@example.com");
        String recordKey = firstRecordKey();

        daily(accessToken, recordKey, RANGE_FROM, RANGE_FROM.plusDays(366))
                .andExpect(status().isBadRequest());
        daily(accessToken, recordKey, RANGE_TO, RANGE_FROM).andExpect(status().isBadRequest());

        daily(accessToken, recordKey, RANGE_FROM, RANGE_FROM.plusDays(365))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("인증 없이 조회하면 401을 반환한다")
    void rejectsUnauthenticated() throws Exception {
        mockMvc.perform(
                        get("/api/v1/health-data/daily")
                                .param("recordkey", "00000000-0000-0000-0000-000000000000")
                                .param("from", RANGE_FROM.toString())
                                .param("to", RANGE_TO.toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("집계 조회가 연결과 날짜 복합 인덱스를 사용한다")
    void usesConnectionDateIndex() throws Exception {
        storeAllFixtures("aggregate-explain@example.com");
        long connectionId = connectionIdOf(firstRecordKey());

        // JPQL이 만드는 SQL 자체가 아니라 같은 조건의 동등한 SQL을 확인. 조건 컬럼과 그룹핑이
        // 같아 옵티마이저가 고르는 인덱스도 같음.
        JsonNode table =
                explainPlanTable(
                        """
                        select activity_date, sum(steps), sum(calories), sum(distance)
                        from health_activity_records
                        where connection_id = ?1
                          and activity_date between ?2 and ?3
                        group by activity_date
                        """,
                        connectionId,
                        RANGE_FROM,
                        RANGE_TO);

        assertUsesConnectionDateIndex(table, "일간 집계");
    }

    @Test
    @DisplayName("월간 조회 응답이 회귀 기준의 월간 값 8행을 그대로 재현한다")
    void monthlyResponseReproducesBaseline() throws Exception {
        String accessToken = storeAllFixtures("aggregate-monthly@example.com");
        Map<RegressionBaseline.MonthlyKey, RegressionBaseline.MonthlyTotal> baseline =
                RegressionBaseline.load().monthlyTotals();
        assertThat(baseline).hasSize(8);

        for (Map.Entry<RegressionBaseline.MonthlyKey, RegressionBaseline.MonthlyTotal> expected :
                baseline.entrySet()) {

            RegressionBaseline.MonthlyKey key = expected.getKey();
            String body =
                    bodyOf(
                            monthly(accessToken, key.recordKey(), MONTH_FROM, MONTH_TO)
                                    .andExpect(status().isOk()));

            // 응답 문자열로 Jackson의 소수 자리 정규화 누락 방지.
            String expectedItem =
                    "{\"month\":\"%s\",\"steps\":%d,\"calories\":%s,\"distance\":%s}"
                            .formatted(
                                    key.month(),
                                    expected.getValue().steps(),
                                    outputScale(expected.getValue().calories()),
                                    outputScale(expected.getValue().distance()));
            assertThat(body).as("%s %s", key.recordKey(), key.month()).contains(expectedItem);
        }
    }

    @Test
    @DisplayName("데이터가 없는 월도 0으로 채우고 조회 범위의 모든 월을 반환한다")
    void fillsMissingMonths() throws Exception {
        String accessToken = storeAllFixtures("aggregate-monthly-fill@example.com");

        // fixture가 2024년 11·12월뿐이므로 9·10월은 채워진 값.
        String body =
                bodyOf(
                        monthly(accessToken, firstRecordKey(), YearMonth.of(2024, 9), MONTH_TO)
                                .andExpect(status().isOk()));

        assertThat(body)
                .contains(expectedMonthlyItemJson(YearMonth.of(2024, 9)))
                .contains(expectedMonthlyItemJson(YearMonth.of(2024, 10)));
        assertThat(readTree(body).get("items")).hasSize(4);
    }

    @Test
    @DisplayName("월간 조회도 범위 상한과 소유권을 강제한다")
    void enforcesMonthlyRangeAndOwnership() throws Exception {
        String accessToken = storeAllFixtures("aggregate-monthly-guard@example.com");
        String recordKey = firstRecordKey();

        monthly(accessToken, recordKey, MONTH_FROM, MONTH_FROM.plusMonths(24))
                .andExpect(status().isBadRequest());
        monthly(accessToken, recordKey, MONTH_TO, MONTH_FROM).andExpect(status().isBadRequest());

        monthly(accessToken, recordKey, MONTH_FROM, MONTH_FROM.plusMonths(23))
                .andExpect(status().isOk());

        String stranger = accessTokenOf("aggregate-monthly-stranger@example.com");
        String unknownBody =
                failureBodyWithoutTrace(
                        monthly(
                                        stranger,
                                        "00000000-0000-0000-0000-000000000000",
                                        MONTH_FROM,
                                        MONTH_TO)
                                .andExpect(status().isNotFound()));
        String otherOwnerBody =
                failureBodyWithoutTrace(
                        monthly(stranger, recordKey, MONTH_FROM, MONTH_TO)
                                .andExpect(status().isNotFound()));

        assertThat(otherOwnerBody).isEqualTo(unknownBody);
    }

    @Test
    @DisplayName("월간 집계 조회가 연결 식별자로 좁혀 들어가고 테이블을 훑지 않는다")
    void monthlyDoesNotScanTable() throws Exception {
        storeAllFixtures("aggregate-monthly-explain@example.com");
        long connectionId = connectionIdOf(firstRecordKey());

        // 월·연 그룹핑에도 날짜 범위 인덱스 사용 확인.
        JsonNode table =
                explainPlanTable(
                        """
                        select year(activity_date), month(activity_date),
                               sum(steps), sum(calories), sum(distance)
                        from health_activity_records
                        where connection_id = ?1
                          and activity_date between ?2 and ?3
                        group by year(activity_date), month(activity_date)
                        """,
                        connectionId,
                        MONTH_FROM.atDay(1),
                        MONTH_TO.atEndOfMonth());

        // 일간처럼 복합 인덱스를 단언하지 않음. 연·월 그룹핑이 인덱스 정렬 이점을 없애고, 월간
        // 요청은 최소 한 달이라 범위 조건이 그 연결의 행을 거의 걸러내지 못함. 실측에서 두 인덱스
        // 비용이 같게 나오며(222.45), 그 상태의 선택은 옵티마이저 tie-break이라 데이터 양이나
        // MySQL 판에 따라 달라짐. 지켜야 하는 성질은 연결 식별자로 좁혀 들어가고 테이블을 통째로
        // 훑지 않는 것.
        assertThat(table.get("access_type").asText())
                .as("월간 집계 접근 방식")
                .isIn("ref", "range", "index_merge");
        assertThat(usedKeyParts(table))
                .as("월간 집계 선행 컬럼")
                .first()
                .isEqualTo("connection_id");
    }

    /**
     * 실행 계획의 table 노드. 조회 전에 테이블 통계를 갱신.
     *
     * <p>함정: 대량 삽입 직후 InnoDB의 카디널리티 추정이 낡아 있어 EXPLAIN이 행 수를 1로 보고 전체
     * 스캔을 고른다. 갱신하지 않으면 이 테스트가 옵티마이저의 판단이 아니라 통계 부재를 확인하게
     * 되고, 인덱스가 실제로 쓰이는지는 검증되지 않는다.
     */
    private JsonNode explainPlanTable(String sql, Object... parameters) {
        entityManager.createNativeQuery("analyze table health_activity_records").getResultList();

        var query = entityManager.createNativeQuery("explain format=json " + sql);
        for (int index = 0; index < parameters.length; index++) {
            query.setParameter(index + 1, parameters[index]);
        }

        JsonNode table = readTree(query.getSingleResult().toString()).findValue("table");
        assertThat(table).as("실행 계획에 table 노드가 없음").isNotNull();

        return table;
    }

    /**
     * 인덱스명을 문자열 포함으로 확인하지 않음. 인덱스명은 {@code possible_keys}에도 실려 있어
     * 테이블 스캔을 골라도 단언이 통과함. 실제로 고른 {@code key}와 쓴 컬럼을 봐야 함.
     */
    private static List<String> usedKeyParts(JsonNode table) {
        List<String> parts = new ArrayList<>();
        table.get("used_key_parts").forEach(part -> parts.add(part.asText()));

        return parts;
    }

    private static void assertUsesConnectionDateIndex(JsonNode table, String label) {
        List<String> usedKeyParts = usedKeyParts(table);

        assertThat(table.get("key").asText())
                .as("%s 인덱스", label)
                .isEqualTo("ix_health_activity_records_connection_date");
        assertThat(table.get("access_type").asText()).as("%s 접근 방식", label).isEqualTo("range");
        assertThat(usedKeyParts)
                .as("%s 사용 컬럼", label)
                .containsExactly("connection_id", "activity_date");

        System.out.printf(
                "%s 실행 계획: key=%s access_type=%s used_key_parts=%s rows=%s%n",
                label,
                table.get("key").asText(),
                table.get("access_type").asText(),
                usedKeyParts,
                table.get("rows_examined_per_scan"));
    }

    @Test
    @DisplayName("저장한 값이 다음 집계 조회에 반영된다")
    void reflectsSavedDataAfterSave() throws Exception {
        String owner = storeAllFixtures("aggregate-fresh@example.com");
        String recordKey = firstRecordKey();

        JsonNode before = itemsOf(daily(owner, recordKey, RANGE_FROM, RANGE_TO));
        long beforeSteps = before.get(14).get("steps").asLong();

        // 같은 fixture의 한 엔트리 측정값만 바꿔 다시 저장.
        ObjectNode changed = (ObjectNode) readTree(java.nio.file.Files.readString(
                fixtureFiles().get(0)));
        ObjectNode entry = (ObjectNode) changed.get("data").get("entries").get(0);
        entry.put("steps", beforeSteps + 1000);
        save(owner, changed.toString()).andExpect(status().isOk());

        JsonNode after = itemsOf(daily(owner, recordKey, RANGE_FROM, RANGE_TO));
        assertThat(after.get(14).get("steps").asLong()).isNotEqualTo(beforeSteps);
    }

    private ResultActions monthly(
            String accessToken, String recordKey, YearMonth from, YearMonth to) throws Exception {

        return mockMvc.perform(
                get("/api/v1/health-data/monthly")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .param("recordkey", recordKey)
                        .param("from", from.toString())
                        .param("to", to.toString()));
    }

    private String storeAllFixtures(String email) throws Exception {
        String accessToken = accessTokenOf(email);
        for (Path file : fixtureFiles()) {
            saveFixture(accessToken, file).andExpect(status().isOk());
        }

        return accessToken;
    }

    private ResultActions daily(String accessToken, String recordKey) throws Exception {
        return daily(accessToken, recordKey, RANGE_FROM, RANGE_TO);
    }

    private ResultActions daily(
            String accessToken, String recordKey, LocalDate from, LocalDate to) throws Exception {

        return mockMvc.perform(
                get("/api/v1/health-data/daily")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .param("recordkey", recordKey)
                        .param("from", from.toString())
                        .param("to", to.toString()));
    }

    private JsonNode itemsOf(ResultActions result) throws Exception {
        return readTree(bodyOf(result.andExpect(status().isOk()))).get("items");
    }

    /** 데이터가 없는 월의 JSON 항목. 원문 문자열로 형식까지 확인. */
    private static String expectedMonthlyItemJson(YearMonth month) {
        return "{\"month\":\"%s\",\"steps\":0,\"calories\":%s,\"distance\":%s}"
                .formatted(
                        month,
                        roundedOutput(BigDecimal.ZERO).toPlainString(),
                        roundedOutput(BigDecimal.ZERO).toPlainString());
    }

    /**
     * 항목 하나가 직렬화되어야 하는 문자열.
     *
     * <p>JSON 트리로 읽으면 trailing zero가 사라질 수 있음.
     * 전송 문자열 자체로 확인.
     */
    private static String expectedItemJson(DailyTotal total) {
        return "{\"date\":\"%s\",\"steps\":%d,\"calories\":%s,\"distance\":%s}"
                .formatted(
                        total.date(),
                        roundedSteps(total.steps()),
                        roundedOutput(total.calories()).toPlainString(),
                        roundedOutput(total.distance()).toPlainString());
    }

    private List<DailyTotal> storedDailyTotals(String recordKey, YearMonth month) {
        return storedDailyTotals(recordKey, month.atDay(1), month.atEndOfMonth());
    }

    private List<DailyTotal> storedDailyTotals(String recordKey, LocalDate from, LocalDate to) {
        return recordRepository.sumDailyTotals(connectionIdOf(recordKey), from, to);
    }

    private long connectionIdOf(String recordKey) {
        return connectionRepository
                .findByRecordKey(recordKey)
                .orElseThrow(() -> new AssertionError("연결이 저장되지 않았습니다: " + recordKey))
                .getId();
    }

    private static BigDecimal sum(
            List<DailyTotal> totals, java.util.function.Function<DailyTotal, BigDecimal> field) {

        return totals.stream().map(field).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static long roundedSteps(BigDecimal value) {
        return value.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private static BigDecimal roundedOutput(BigDecimal value) {
        return value.setScale(OUTPUT_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 회귀 기준의 값을 응답 정밀도 문자열로.
     *
     * <p>반올림 모드를 넘김. 회귀 기준 표의 값은 지금 소수점 여섯 자리지만, 모드 없이
     * {@code setScale}만 부르면 표에 일곱 자리가 적히는 순간 기대값 불일치가 아니라
     * {@code ArithmeticException}으로 죽어 실패 원인이 엉뚱하게 보인다.
     */
    private static String outputScale(BigDecimal value) {
        return roundedOutput(value).toPlainString();
    }
}
