package com.okcare.assignment.health.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.okcare.assignment.health.domain.DailyTotal;
import com.okcare.assignment.health.domain.MonthlyTotal;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 캐시가 조용히 낡은 값을 내보내거나, Redis 한 번 끊겨서 조회가 죽는 것이 이 계층에서 가장 비싼
 * 실수. 둘 다 정상 경로만 보면 드러나지 않으므로 키 조립과 예외 경로를 좁혀 확인.
 */
class HealthAggregationCacheTest {

    private static final String RECORD_KEY = "7836887b-b12a-440f-af0f-851546504b13";
    private static final LocalDate FROM = LocalDate.of(2024, 11, 1);
    private static final LocalDate TO = LocalDate.of(2024, 12, 31);
    private static final YearMonth MONTH_FROM = YearMonth.of(2024, 11);
    private static final YearMonth MONTH_TO = YearMonth.of(2024, 12);

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);

    private final ObjectMapper objectMapper =
            new ObjectMapper().registerModule(new JavaTimeModule());

    private final HealthAggregationCache cache =
            new HealthAggregationCache(redisTemplate, objectMapper);

    @BeforeEach
    void bindValueOperations() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
    }

    @Test
    @DisplayName("version 키가 없으면 초기값으로 키를 만들고 조회가 그 키를 생성하지 않는다")
    void treatsMissingVersionAsInitial() {
        given(valueOperations.get(anyString())).willReturn(null);

        cache.daily(RECORD_KEY, FROM, TO, () -> List.of());

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(key.capture(), anyString(), any(Duration.class));
        assertThat(key.getValue())
                .isEqualTo("health:daily:" + RECORD_KEY + ":0:2024-11-01:2024-12-31");

        // 조회가 version 키를 만들면 저장 없이도 버전이 올라 캐시가 매번 비적중이 됨.
        verify(valueOperations, never()).increment(anyString());
    }

    @Test
    @DisplayName("저장된 version을 키에 넣는다")
    void putsStoredVersionInKey() {
        given(valueOperations.get("health:cache-version:" + RECORD_KEY)).willReturn("7");

        cache.monthly(RECORD_KEY, MONTH_FROM, MONTH_TO, () -> List.of());

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(key.capture(), anyString(), any(Duration.class));
        assertThat(key.getValue())
                .isEqualTo("health:monthly:" + RECORD_KEY + ":7:2024-11:2024-12");
    }

    @Test
    @DisplayName("일간 5분, 월간 10분 TTL로 저장한다")
    void appliesSpecifiedTtl() {
        given(valueOperations.get(anyString())).willReturn(null);

        cache.daily(RECORD_KEY, FROM, TO, () -> List.of());
        cache.monthly(RECORD_KEY, MONTH_FROM, MONTH_TO, () -> List.of());

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations, times(2))
                .set(anyString(), anyString(), ttl.capture());
        assertThat(ttl.getAllValues())
                .containsExactly(Duration.ofMinutes(5), Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("적중하면 적재 함수를 부르지 않는다")
    void skipsLoaderOnHit() throws Exception {
        List<DailyTotal> stored =
                List.of(new DailyTotal(FROM, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO));
        given(valueOperations.get("health:cache-version:" + RECORD_KEY)).willReturn(null);
        given(valueOperations.get(startsWith("health:daily:")))
                .willReturn(objectMapper.writeValueAsString(stored));

        AtomicInteger loads = new AtomicInteger();
        List<DailyTotal> returned =
                cache.daily(
                        RECORD_KEY,
                        FROM,
                        TO,
                        () -> {
                            loads.incrementAndGet();
                            return List.of();
                        });

        assertThat(returned).isEqualTo(stored);
        assertThat(loads.get()).isZero();
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("적중값의 소수점 열두 자리를 scale까지 그대로 복원한다")
    void keepsScaleOnRoundTrip() throws Exception {
        BigDecimal steps = new BigDecimal("7243.499999999900");
        BigDecimal zero = new BigDecimal("0.000000000000");
        // 세 값을 서로 다르게 둠. 같은 값을 쓰면 한 측정값만 어긋나도 드러나지 않음.
        BigDecimal distance = new BigDecimal("5.419490123456");
        List<MonthlyTotal> stored = List.of(new MonthlyTotal(MONTH_FROM, steps, zero, distance));
        given(valueOperations.get("health:cache-version:" + RECORD_KEY)).willReturn(null);
        given(valueOperations.get(startsWith("health:monthly:")))
                .willReturn(objectMapper.writeValueAsString(stored));

        MonthlyTotal returned =
                cache.monthly(RECORD_KEY, MONTH_FROM, MONTH_TO, () -> List.of()).get(0);

        // scale이 줄면 응답 반올림 결과는 같아도 값의 정밀도가 조용히 깎임. isEqualTo가 scale까지
        // 비교하므로 isEqualByComparingTo로 바꾸면 0E-12와 0.000000000000을 구분하지 못함.
        assertThat(returned.steps()).isEqualTo(steps);
        assertThat(returned.calories()).isEqualTo(zero);
        assertThat(returned.calories().scale()).isEqualTo(12);
        assertThat(returned.distance()).isEqualTo(distance);
        assertThat(returned.month()).isEqualTo(MONTH_FROM);
    }

    @Test
    @DisplayName("적중값을 읽을 수 없으면 비적중으로 취급해 다시 채운다")
    void treatsUnreadableHitAsMiss() {
        given(valueOperations.get("health:cache-version:" + RECORD_KEY)).willReturn(null);
        given(valueOperations.get(startsWith("health:daily:")))
                .willReturn("{망가진 값}");

        List<DailyTotal> loaded =
                List.of(new DailyTotal(FROM, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO));

        // 예외를 올리면 캐시 형식이 바뀐 배포 직후 조회가 전부 실패함.
        assertThat(cache.daily(RECORD_KEY, FROM, TO, () -> loaded)).isEqualTo(loaded);
        verify(valueOperations).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("캐시 값이 JSON null이면 비적중으로 취급한다")
    void treatsJsonNullHitAsMiss() {
        given(valueOperations.get("health:cache-version:" + RECORD_KEY)).willReturn(null);
        given(valueOperations.get(startsWith("health:daily:"))).willReturn("null");

        List<DailyTotal> loaded =
                List.of(new DailyTotal(FROM, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO));

        // readValue가 예외 없이 null을 돌려주므로 그대로 반환하면 응답 변환의 stream()에서 NPE가
        // 나고 조회가 500이 됨. 문법 오류만 잡으면 이 경로가 남음.
        assertThat(cache.daily(RECORD_KEY, FROM, TO, () -> loaded)).isEqualTo(loaded);
        verify(valueOperations).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("조회가 실패하면 적재 결과를 돌려주고 저장을 시도하지 않는다")
    void fallsBackWhenReadFails() {
        willThrow(new QueryTimeoutException("redis down")).given(valueOperations).get(anyString());

        List<DailyTotal> loaded =
                List.of(new DailyTotal(FROM, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO));

        assertThat(cache.daily(RECORD_KEY, FROM, TO, () -> loaded)).isEqualTo(loaded);

        // 읽지 못한 version으로 키를 만들어 저장하면 어느 세대의 값인지 알 수 없는 항목이 남음.
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("저장이 실패해도 적재 결과를 돌려준다")
    void returnsLoadedWhenWriteFails() {
        given(valueOperations.get(anyString())).willReturn(null);
        willThrow(new QueryTimeoutException("redis down"))
                .given(valueOperations)
                .set(anyString(), anyString(), any(Duration.class));

        List<MonthlyTotal> loaded =
                List.of(
                        new MonthlyTotal(
                                MONTH_FROM, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO));

        assertThat(cache.monthly(RECORD_KEY, MONTH_FROM, MONTH_TO, () -> loaded)).isEqualTo(loaded);
    }

    @Test
    @DisplayName("무효화는 version을 증가시키고 실패해도 예외를 올리지 않는다")
    void invalidateIncrementsAndSwallowsFailure() {
        cache.invalidate(RECORD_KEY);
        verify(valueOperations).increment("health:cache-version:" + RECORD_KEY);

        willThrow(new QueryTimeoutException("redis down"))
                .given(valueOperations)
                .increment(anyString());

        // 저장은 이미 커밋됐으므로 여기서 실패를 알리면 클라이언트가 저장 실패로 오해함.
        assertThatCode(() -> cache.invalidate(RECORD_KEY)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("키 공간을 훑는 명령을 쓰지 않는다")
    void neverScansKeyspace() {
        given(valueOperations.get(anyString())).willReturn(null);

        cache.daily(RECORD_KEY, FROM, TO, () -> List.of());
        cache.invalidate(RECORD_KEY);

        // KEYS나 패턴 삭제는 키가 늘어나면 Redis를 멈추게 함. 이전 version 키는 TTL로 만료시킴.
        verify(redisTemplate, never()).keys(anyString());
        verify(redisTemplate, never()).delete(eq(RECORD_KEY));
        // 이전 version 키는 TTL로 만료시킴.
    }
}
