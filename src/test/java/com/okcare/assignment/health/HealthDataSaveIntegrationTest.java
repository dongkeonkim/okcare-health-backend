package com.okcare.assignment.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.okcare.assignment.RegressionBaseline;
import com.okcare.assignment.health.domain.HealthActivityRecord;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 과제가 제공한 네 JSON을 실제 MySQL에 저장해 개발 계획이 경계 검증으로 요구한
 * 저장·재전송·변경·권한을 확인.
 *
 * <p>인메모리 DB로 바꾸면 UNIQUE 제약과 {@code DECIMAL} 저장 정밀도라는 검증 대상 자체가 사라짐.
 * 인증도 실제 필터를 거쳐야 보호 경로 계약이 함께 검증됨.
 */
class HealthDataSaveIntegrationTest extends HealthIntegrationSupport {

    @Test
    @DisplayName("네 JSON을 저장하면 회귀 기준의 수신 건수만큼 새로 저장된다")
    void savesEveryFixtureRecord() throws Exception {
        String accessToken = accessTokenOf("health-save@example.com");
        int expected = RegressionBaseline.load().count("총 수신 레코드");

        int received = 0;
        int inserted = 0;
        long elapsedMillis = 0;
        for (Path file : fixtureFiles()) {
            long startedAt = System.nanoTime();
            JsonNode body =
                    readTree(
                            bodyOf(saveFixture(accessToken, file).andExpect(status().isOk())));
            elapsedMillis += (System.nanoTime() - startedAt) / 1_000_000;

            received += body.get("received").asInt();
            inserted += body.get("inserted").asInt();
            assertThat(body.get("updated").asInt()).isZero();
            assertThat(body.get("duplicated").asInt()).isZero();
        }

        assertThat(received).isEqualTo(expected);
        assertThat(inserted).isEqualTo(expected);
        assertThat(recordRepository.count()).isEqualTo(expected);

        // 저장 방식 전환 판단이 테스트 출력에 드러나게 함. JPA는 AUTO_INCREMENT 때문에 insert 문을
        // 배치하지 못하므로, 느려지면 JdbcTemplate 배치로 옮길 신호.
        System.out.printf("네 JSON 저장 소요: %dms (%d건)%n", elapsedMillis, expected);
    }

    @Test
    @DisplayName("같은 JSON을 다시 보내면 전부 duplicated이고 저장 레코드 수가 늘지 않는다")
    void resendDoesNotGrowStoredRecords() throws Exception {
        String accessToken = accessTokenOf("health-resend@example.com");
        Path file = fixtureFiles().get(0);

        JsonNode first =
                readTree(bodyOf(saveFixture(accessToken, file).andExpect(status().isOk())));
        long countAfterFirst = recordRepository.count();

        JsonNode second =
                readTree(bodyOf(saveFixture(accessToken, file).andExpect(status().isOk())));

        assertThat(second.get("received").asInt()).isEqualTo(first.get("received").asInt());
        assertThat(second.get("duplicated").asInt()).isEqualTo(first.get("inserted").asInt());
        assertThat(second.get("inserted").asInt()).isZero();
        assertThat(second.get("updated").asInt()).isZero();
        assertThat(recordRepository.count()).isEqualTo(countAfterFirst);
    }

    @Test
    @DisplayName("한 엔트리의 측정값만 바꿔 보내면 그 한 건만 updated가 된다")
    void changedMeasurementIsUpdatedOnce() throws Exception {
        String accessToken = accessTokenOf("health-changed@example.com");
        Path file = fixtureFiles().get(0);
        saveFixture(accessToken, file).andExpect(status().isOk());

        long countAfterFirst = recordRepository.count();
        ObjectNode changed = (ObjectNode) readTree(Files.readString(file));
        ObjectNode entry = (ObjectNode) changed.at("/data/entries/0");
        entry.put("steps", 99999);

        JsonNode body =
                readTree(bodyOf(save(accessToken, changed.toString()).andExpect(status().isOk())));

        assertThat(body.get("updated").asInt()).isEqualTo(1);
        assertThat(body.get("inserted").asInt()).isZero();
        assertThat(body.get("duplicated").asInt())
                .isEqualTo(body.get("received").asInt() - 1);
        assertThat(recordRepository.count()).isEqualTo(countAfterFirst);
    }

    @Test
    @DisplayName("저장한 측정값이 저장 정밀도로 그대로 돌아온다")
    void storesQuantizedMeasurementsWithoutLoss() throws Exception {
        // 양자화한 값과 DECIMAL(24,12)가 어긋나면 여기서 드러남. MySQL이 추가로 반올림하면
        // 재전송 판정과 집계가 함께 어긋남.
        String accessToken = accessTokenOf("health-precision@example.com");
        Path file = fixtureFiles().get(2);
        saveFixture(accessToken, file).andExpect(status().isOk());

        List<HealthActivityRecord> stored = recordRepository.findAll();

        assertThat(stored).isNotEmpty();
        assertThat(stored)
                .allSatisfy(
                        record -> {
                            assertThat(record.getSteps().scale()).isEqualTo(12);
                            assertThat(record.getCalories().scale()).isEqualTo(12);
                            assertThat(record.getDistance().scale()).isEqualTo(12);
                        });
        assertThat(stored.stream().map(HealthActivityRecord::getSteps))
                .anySatisfy(
                        steps ->
                                assertThat(steps.stripTrailingZeros().scale())
                                        .isEqualTo(12));
    }

    @Test
    @DisplayName("최초 저장이 recordkey를 인증된 회원에게 연결한다")
    void firstSaveClaimsRecordKey() throws Exception {
        String accessToken = accessTokenOf("health-owner@example.com");
        Path file = fixtureFiles().get(0);

        saveFixture(accessToken, file).andExpect(status().isOk());

        String recordKey = readTree(Files.readString(file)).get("recordkey").asText();
        assertThat(connectionRepository.findByRecordKey(recordKey)).isPresent();
    }

    @Test
    @DisplayName("다른 회원이 소유한 recordkey로 저장하면 409를 반환하고 레코드가 늘지 않는다")
    void rejectsOtherMembersRecordKey() throws Exception {
        String ownerToken = accessTokenOf("health-claimed@example.com");
        String otherToken = accessTokenOf("health-intruder@example.com");
        Path file = fixtureFiles().get(0);

        saveFixture(ownerToken, file).andExpect(status().isOk());
        long countAfterOwner = recordRepository.count();

        saveFixture(otherToken, file)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HEALTH_RECORD_KEY_CONFLICT"));

        assertThat(recordRepository.count()).isEqualTo(countAfterOwner);
    }

    @Test
    @DisplayName("같은 회원이 같은 recordkey로 동시에 저장해도 두 요청 모두 성공한다")
    void concurrentFirstSaveBySameMemberSucceedsBoth() throws Exception {
        // 두 writer가 서로 커밋 전에 기존 행을 조회하면 양쪽이 insert로 분류하고, 패자가
        // uk_health_activity_records_identity 위반을 받음. 그 위반을 다루지 않으면 정상 재전송이
        // 500으로 나감. UNIQUE는 중복 행을 막지만 응답 계약까지 지켜 주지는 않음.
        String accessToken = accessTokenOf("health-race-same@example.com");
        String body = Files.readString(fixtureFiles().get(0));

        List<Integer> statuses = saveConcurrently(accessToken, accessToken, body);

        assertThat(statuses).allMatch(status -> status == 200);
        assertThat(recordRepository.count()).isEqualTo(readTree(body).at("/data/entries").size());
    }

    @Test
    @DisplayName("다른 회원이 같은 recordkey로 동시에 저장하면 한쪽만 성공한다")
    void concurrentFirstSaveByDifferentMembersLeavesOneOwner() throws Exception {
        // 연결 생성 경쟁의 패자는 새 트랜잭션에서 승자의 연결을 다시 읽고 소유자가 다르므로 409.
        String first = accessTokenOf("health-race-a@example.com");
        String second = accessTokenOf("health-race-b@example.com");
        String body = Files.readString(fixtureFiles().get(0));

        List<Integer> statuses = saveConcurrently(first, second, body);

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(connectionRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("한 요청 안에 같은 식별자가 있으면 마지막 값을 저장하고 하나로만 계산한다")
    void collapsesDuplicateIdentityWithinRequest() throws Exception {
        // fixture에는 원본 내 중복이 0건이라 이 경로가 실행되지 않음. received는 원본 엔트리 수
        // 그대로여서 나머지 카운트의 합보다 클 수 있다는 계약도 여기서만 드러남.
        String accessToken = accessTokenOf("health-collapse@example.com");
        ObjectNode payload = (ObjectNode) readTree(Files.readString(fixtureFiles().get(0)));
        ObjectNode entries = (ObjectNode) payload.at("/data");
        JsonNode first = payload.at("/data/entries/0");

        ObjectNode repeated = first.deepCopy();
        repeated.put("steps", 77777);
        ((com.fasterxml.jackson.databind.node.ArrayNode) entries.get("entries")).add(repeated);

        JsonNode body =
                readTree(bodyOf(save(accessToken, payload.toString()).andExpect(status().isOk())));

        int received = body.get("received").asInt();
        assertThat(received).isEqualTo(entries.get("entries").size());
        assertThat(body.get("inserted").asInt()).isEqualTo(received - 1);
        assertThat(body.get("updated").asInt()).isZero();
        assertThat(body.get("duplicated").asInt()).isZero();
        assertThat(recordRepository.count()).isEqualTo(received - 1L);

        // 마지막 항목이 이겨야 함. 첫 항목이 남으면 요청 순서가 결과를 바꾸지 못한다는 계약이 깨짐.
        assertThat(recordRepository.findAll())
                .anySatisfy(
                        record ->
                                assertThat(record.getSteps())
                                        .isEqualByComparingTo(new BigDecimal("77777")));
    }

    @Test
    @DisplayName("인증 없이 저장하면 401을 반환한다")
    void rejectsUnauthenticatedSave() throws Exception {
        // 새 경로는 SecurityConfig의 anyRequest().authenticated()가 자동으로 막아야 함. 공개 목록에
        // 실수로 들어가면 여기서 드러남.
        mockMvc.perform(
                        post("/api/v1/health-data")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(Files.readString(fixtureFiles().get(0))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_ACCESS_TOKEN_INVALID"));
    }

    @Test
    @DisplayName("지원하지 않는 단위는 400을 반환하고 아무 것도 저장하지 않는다")
    void rejectsUnsupportedUnitWithoutStoring() throws Exception {
        String accessToken = accessTokenOf("health-unit@example.com");
        ObjectNode broken = (ObjectNode) readTree(Files.readString(fixtureFiles().get(0)));
        ((ObjectNode) broken.at("/data/entries/0/distance")).put("unit", "m");

        save(accessToken, broken.toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("HEALTH_DATA_INVALID"));

        // 정규화가 트랜잭션 밖에서 끝나므로 한 엔트리가 잘못되면 전체가 저장되지 않아야 함.
        assertThat(recordRepository.count()).isZero();
        assertThat(connectionRepository.count()).isZero();
    }

    /**
     * 두 토큰으로 같은 본문을 동시에 저장하고 상태 코드만 돌려줌.
     *
     * <p>{@code CountDownLatch}로 시작을 맞춤. 이것만으로 두 트랜잭션이 반드시 겹치지는 않지만,
     * 겹치는 순간이 있으면 잡음.
     */
    private List<Integer> saveConcurrently(String first, String second, String body)
            throws Exception {

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> left = pool.submit(saveTask(start, first, body));
            Future<Integer> right = pool.submit(saveTask(start, second, body));
            start.countDown();

            return List.of(left.get(60, TimeUnit.SECONDS), right.get(60, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private Callable<Integer> saveTask(CountDownLatch start, String accessToken, String body) {
        return () -> {
            start.await();
            return save(accessToken, body).andReturn().getResponse().getStatus();
        };
    }

}
