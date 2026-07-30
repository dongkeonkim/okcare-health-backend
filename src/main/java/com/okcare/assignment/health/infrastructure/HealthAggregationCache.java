package com.okcare.assignment.health.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.okcare.assignment.health.domain.DailyTotal;
import com.okcare.assignment.health.domain.MonthlyTotal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 집계 조회 캐시. Cache-Aside.
 *
 * <p>반올림하지 않은 합계를 캐시. 응답 형식으로 굳혀 캐시하면 적중 경로와 비적중 경로가 서로 다른
 * 코드를 지나게 되고, 기능 명세가 요구하는 "캐시 장애 여부에 따라 응답 형식과 집계값이 달라지지
 * 않음"이 구조로 보장되지 않음.
 *
 * <p>적재 함수를 인자로 받는 이유는 version을 한 번만 읽기 위함. 조회와 저장을 따로 노출하면
 * 호출부가 version을 두 번 읽고, 그 사이에 저장이 끼어들면 저장 전 값이 새 version 키에 실려 TTL
 * 내내 낡은 값이 나감.
 *
 * <p>모든 Redis 오류를 삼키고 적재 함수로 대체. 오류 로그에 {@code recordkey}와 측정값을 남기지
 * 않음.
 */
@Component
public class HealthAggregationCache {

    private static final Logger log = LoggerFactory.getLogger(HealthAggregationCache.class);

    private static final String VERSION_PREFIX = "health:cache-version:";
    private static final String DAILY_PREFIX = "health:daily:";
    private static final String MONTHLY_PREFIX = "health:monthly:";

    static final Duration DAILY_TTL = Duration.ofMinutes(5);
    static final Duration MONTHLY_TTL = Duration.ofMinutes(10);

    /** version 키가 없을 때 쓰는 값. 조회는 이 키를 만들지 않고 저장 경로만 증가시킴. */
    private static final long INITIAL_VERSION = 0L;

    private static final TypeReference<List<DailyTotal>> DAILY_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<MonthlyTotal>> MONTHLY_TYPE = new TypeReference<>() {};

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public HealthAggregationCache(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public List<DailyTotal> daily(
            String recordKey, LocalDate from, LocalDate to, Supplier<List<DailyTotal>> loader) {

        return cached(DAILY_PREFIX, DAILY_TTL, recordKey, from + ":" + to, DAILY_TYPE, loader);
    }

    public List<MonthlyTotal> monthly(
            String recordKey, YearMonth from, YearMonth to, Supplier<List<MonthlyTotal>> loader) {

        return cached(
                MONTHLY_PREFIX, MONTHLY_TTL, recordKey, from + ":" + to, MONTHLY_TYPE, loader);
    }

    /**
     * 저장 후 무효화. 이전 version의 키는 지우지 않고 TTL로 만료시킴.
     *
     * <p>{@code KEYS}나 패턴 삭제를 쓰지 않음. 키 공간을 훑는 명령은 Redis를 멈추게 함.
     *
     * <p>실패해도 예외를 던지지 않음. 저장은 이미 커밋됐으므로 되돌릴 수 없고, 여기서 실패를 알리면
     * 클라이언트가 저장이 안 된 것으로 오해함. 대가는 TTL 동안 낡은 값이 나갈 수 있다는 것.
     */
    public void invalidate(String recordKey) {
        try {
            redisTemplate.opsForValue().increment(VERSION_PREFIX + recordKey);
        } catch (RuntimeException e) {
            log.warn("집계 캐시 무효화 실패 type={}", e.getClass().getSimpleName());
        }
    }

    private <T> List<T> cached(
            String keyPrefix,
            Duration ttl,
            String recordKey,
            String range,
            TypeReference<List<T>> type,
            Supplier<List<T>> loader) {

        String key;
        String hit;
        try {
            // version 읽기와 값 읽기를 한 try에 둠. 값 읽기만 실패했을 때 적재 결과를 저장하면
            // 방금 읽은 version이 유효한지 확신할 수 없는 상태에서 씀.
            key = keyPrefix + recordKey + ":" + readVersion(recordKey) + ":" + range;
            hit = redisTemplate.opsForValue().get(key);
        } catch (RuntimeException e) {
            // DataAccessException만 잡지 않음. 이 경로의 계약이 "무슨 일이 있어도 응답을 깨뜨리지
            // 않음"이고, 캐시 문제로 500을 내면 기능 명세의 조회 가용성 요구를 어김. 적재 함수는
            // try 밖이라 여기서 삼켜도 조회 자체의 버그를 가리지 않음.
            log.warn(
                    "집계 캐시 조회 실패 prefix={} type={}",
                    keyPrefix,
                    e.getClass().getSimpleName());
            return loader.get();
        }

        if (hit != null) {
            try {
                List<T> parsed = objectMapper.readValue(hit, type);
                // 함정: 값이 JSON {@code null}이면 readValue가 예외 없이 null을 돌려줌. 그대로
                // 반환하면 응답 변환의 stream()에서 NPE가 나 조회가 500이 됨. 문법 오류만 잡으면
                // 이 경로가 남음.
                if (parsed != null) {
                    return parsed;
                }
            } catch (Exception e) {
                // 캐시 형식이 배포 사이에 바뀌면 남아 있는 값을 읽을 수 없음. 예외를 올리면 배포
                // 직후 조회가 전부 실패함.
            }

            log.warn("집계 캐시 값을 쓸 수 없어 비적중으로 처리 prefix={}", keyPrefix);
        }

        List<T> loaded = loader.get();
        put(key, loaded, ttl, keyPrefix);

        return loaded;
    }

    /**
     * 키가 없으면 조회가 만들지 않고 초기값으로 간주.
     *
     * <p>{@code INCR}만 쓰는 키라 정수가 아닐 수 없지만, 외부에서 손대면
     * {@code NumberFormatException}이 남. 그것이 {@code DataAccessException}이 아니어서 호출부의
     * {@code catch}를 빠져나가 조회가 500이 됨. 호출부가 {@code RuntimeException}을 잡는 이유.
     */
    private long readVersion(String recordKey) {
        String stored = redisTemplate.opsForValue().get(VERSION_PREFIX + recordKey);

        return stored == null ? INITIAL_VERSION : Long.parseLong(stored);
    }

    private void put(String key, Object value, Duration ttl, String keyPrefix) {
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
        } catch (Exception e) {
            // 저장 실패는 다음 조회가 다시 DB를 보는 것으로 끝남. 응답에 영향을 주지 않음.
            log.warn(
                    "집계 캐시 저장 실패 prefix={} type={}",
                    keyPrefix,
                    e.getClass().getSimpleName());
        }
    }
}
