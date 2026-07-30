package com.okcare.assignment.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
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
 * 저장한 fixture를 실제 MySQL에서 집계해 기능 명세의 월간 회귀값과 대조.
 *
 * <p>일간 기대값은 명세에 없음. 그래서 일간은 반올림 전 합계를 월별로 더해 월간 회귀값으로 굴러
 * 올라가는지로 검증. 이 대조가 날짜 경계 검증도 겸함. UTC 날짜와 서울 날짜가 다른 엔트리가
 * 450건이고 그중 11건이 월을 넘으므로, 타임존 처리가 어긋나면 두 달 값이 동시에 틀어짐.
 */
class HealthAggregationIntegrationTest extends HealthIntegrationSupport {

    private static final LocalDate RANGE_FROM = LocalDate.of(2024, 11, 1);
    private static final LocalDate RANGE_TO = LocalDate.of(2024, 12, 31);
    private static final LocalDate FIRST_STORED_DATE = LocalDate.of(2024, 11, 15);
    private static final int OUTPUT_SCALE = 6;

    @Autowired private EntityManager entityManager;

    @Test
    @DisplayName("조회 범위의 모든 날짜를 오래된 날짜부터 반환한다")
    void returnsEveryDateInRange() throws Exception {
        String accessToken = storeAllFixtures("aggregate-range@example.com");

        JsonNode items = itemsOf(daily(accessToken, firstRecordKey(), RANGE_FROM, RANGE_TO));

        // 항목 수와 양 끝 날짜를 함께 단언. 채우기 누락과 off-by-one이 한 번에 걸림.
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

        // 상한과 같은 366일은 통과. 한쪽만 확인하면 off-by-one이 남음.
        daily(accessToken, recordKey, RANGE_FROM, RANGE_FROM.plusDays(365))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("인증 없이 조회하면 401을 반환한다")
    void rejectsUnauthenticated() throws Exception {
        mockMvc.perform(
                        get("/api/v1/health-data/daily")
                                .param("recordKey", "00000000-0000-0000-0000-000000000000")
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
        String plan =
                entityManager
                        .createNativeQuery(
                                """
                                explain format=json
                                select activity_date, sum(steps), sum(calories), sum(distance)
                                from health_activity_records
                                where connection_id = ?1
                                  and activity_date between ?2 and ?3
                                group by activity_date
                                """)
                        .setParameter(1, connectionId)
                        .setParameter(2, RANGE_FROM)
                        .setParameter(3, RANGE_TO)
                        .getSingleResult()
                        .toString();

        // 문자열 포함으로 확인하지 않음. 인덱스명은 possible_keys에도 실려 있어서, MySQL이 테이블
        // 스캔을 골라도 단언이 통과함. 실제로 고른 값인 key와 쓴 컬럼을 봐야 함.
        JsonNode table = readTree(plan).findValue("table");
        assertThat(table).as("실행 계획에 table 노드가 없음").isNotNull();
        assertThat(table.get("key").asText())
                .isEqualTo("ix_health_activity_records_connection_date");
        assertThat(table.get("access_type").asText()).isEqualTo("range");

        List<String> usedKeyParts = new ArrayList<>();
        table.get("used_key_parts").forEach(part -> usedKeyParts.add(part.asText()));
        assertThat(usedKeyParts).containsExactly("connection_id", "activity_date");

        System.out.printf(
                "일간 집계 실행 계획: key=%s access_type=%s used_key_parts=%s rows=%s%n",
                table.get("key").asText(),
                table.get("access_type").asText(),
                usedKeyParts,
                table.get("rows_examined_per_scan"));
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
                        .param("recordKey", recordKey)
                        .param("from", from.toString())
                        .param("to", to.toString()));
    }

    private JsonNode itemsOf(ResultActions result) throws Exception {
        return readTree(bodyOf(result.andExpect(status().isOk()))).get("items");
    }

    /**
     * 항목 하나가 직렬화되어야 하는 문자열.
     *
     * <p>측정값을 JSON 트리로 읽어 비교하지 않음. Jackson은 실수를 {@code double}로 읽고,
     * {@code BigDecimal}로 읽게 바꿔도 기본 node factory가 trailing zero를 떼어낸다. 어느 쪽이든
     * {@code 0.000000}이 {@code 0}으로 보여 소수점 여섯 자리 유지가 검증 대상에서 빠짐. 계약이
     * 전송되는 문자열 자체이므로 원문에서 확인.
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

    private static String firstRecordKey() throws Exception {
        return RegressionBaseline.load().monthlyTotals().keySet().iterator().next().recordKey();
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
}
