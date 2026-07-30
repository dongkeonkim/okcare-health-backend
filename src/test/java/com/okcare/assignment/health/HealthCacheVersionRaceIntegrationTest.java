package com.okcare.assignment.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.okcare.assignment.health.infrastructure.HealthAggregationCache;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 조회가 소유권을 확인한 뒤 Redis version을 읽기 전에 다른 요청이 저장을 커밋하는 경쟁을 만듦.
 *
 * <p>조회 경로가 하나의 DB 스냅샷을 version 읽기까지 들고 있으면 저장 전 값이 새 version 키에 실려
 * TTL 내내 후속 조회에도 나감. 저장과 조회를 순차로 실행하는 테스트로는 이 창을 만들 수 없어 캐시
 * 진입 직전을 붙잡음.
 *
 * <p>스파이를 쓰는 이유는 요청 <em>안</em>의 두 시점을 제어해야 하기 때문. 두 요청을 동시에 보내는
 * 방식으로는 어느 쪽이 어디까지 갔는지 정할 수 없어 경쟁이 재현되지 않음.
 *
 * <p>{@code sleep}으로 기다리지 않음. 조회가 관문에 도착했다는 신호를 받고 저장을 시작해야 경쟁이
 * 결정적으로 만들어짐. 도착 전에 저장하면 붙잡히지 않고 지나가 결함이 있어도 통과함.
 */
class HealthCacheVersionRaceIntegrationTest extends HealthIntegrationSupport {

    private final AtomicBoolean pauseNextCacheLookup = new AtomicBoolean();

    /**
     * 관문이 저장 완료 신호로 열렸는지.
     *
     * <p>{@code await} 반환값을 버리면 느린 환경에서 대기가 만료돼 조회가 스스로 진행하고, 결함이
     * 복원돼 있어도 테스트가 통과함. 시간 초과를 통과가 아니라 실패로 만들기 위해 기록.
     */
    private final AtomicBoolean gateOpenedBySave = new AtomicBoolean();
    private final CountDownLatch arrivedAtGate = new CountDownLatch(1);
    private final CountDownLatch mayProceed = new CountDownLatch(1);

    @MockitoSpyBean private HealthAggregationCache spiedCache;

    @Test
    @DisplayName("소유권 조회와 version 읽기 사이에 저장이 커밋되어도 낡은 값이 캐시에 남지 않는다")
    void neverCachesPreSaveDataUnderNewVersion() throws Exception {
        String accessToken = accessTokenOf("cache-race@example.com");
        String original = Files.readString(fixtureFiles().get(0));
        String recordKey = readTree(original).get("recordkey").asText();
        save(accessToken, original).andExpect(status().isOk());

        long before = firstDaySteps(accessToken, recordKey);
        redisTemplate.delete(redisTemplate.keys("health:*"));
        givenCacheLookupPausesOnce();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pauseNextCacheLookup.set(true);
            Future<Long> paused = pool.submit(() -> firstDaySteps(accessToken, recordKey));

            assertThat(arrivedAtGate.await(30, TimeUnit.SECONDS))
                    .as("조회가 캐시 진입 관문에 도달")
                    .isTrue();
            save(accessToken, changedFirstEntry(original, before + 1000))
                    .andExpect(status().isOk());
            mayProceed.countDown();
            paused.get(30, TimeUnit.SECONDS);
        } finally {
            mayProceed.countDown();
            pool.shutdownNow();
        }

        assertThat(gateOpenedBySave.get())
                .as("관문이 시간 초과가 아니라 저장 완료 신호로 열림")
                .isTrue();

        // 저장이 끝난 뒤 시작하는 조회는 저장 후 값을 봐야 함. 붙잡힌 조회가 저장 전 값을 새
        // version 키에 넣어 두면 이 단언이 깨짐.
        assertThat(firstDaySteps(accessToken, recordKey)).isNotEqualTo(before);
    }

    /**
     * 캐시 진입 직전에 멈춤. 서비스가 소유권을 확인한 <em>뒤</em>, 캐시가 version을 읽기
     * <em>전</em>이 정확히 이 지점.
     *
     * <p>리포지토리를 스파이하지 않음. Spring Data 리포지토리는 인터페이스 프록시라 스파이에서
     * {@code callRealMethod()}가 실패해 조회가 500이 됨. 캐시는 구체 클래스라 동작.
     */
    private void givenCacheLookupPausesOnce() {
        willAnswer(
                        call -> {
                            if (pauseNextCacheLookup.compareAndSet(true, false)) {
                                arrivedAtGate.countDown();
                                gateOpenedBySave.set(mayProceed.await(30, TimeUnit.SECONDS));
                            }

                            return call.callRealMethod();
                        })
                .given(spiedCache)
                .daily(any(), any(), any(), any());
    }

    private String changedFirstEntry(String body, long steps) {
        ObjectNode changed = (ObjectNode) readTree(body);
        ObjectNode entry = (ObjectNode) changed.get("data").get("entries").get(0);
        entry.put("steps", steps);

        return changed.toString();
    }

    private long firstDaySteps(String accessToken, String recordKey) {
        try {
            ResultActions result =
                    mockMvc.perform(
                            get("/api/v1/health-data/daily")
                                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                                    .param("recordKey", recordKey)
                                    .param("from", "2024-11-15")
                                    .param("to", "2024-11-15"));

            return readTree(bodyOf(result.andExpect(status().isOk())))
                    .get("items")
                    .get(0)
                    .get("steps")
                    .asLong();
        } catch (Exception e) {
            throw new AssertionError("조회에 실패했습니다.", e);
        }
    }
}
