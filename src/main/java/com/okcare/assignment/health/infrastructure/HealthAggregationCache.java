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
 * 반올림 전 집계의 Cache-Aside 저장소.
 *
 * <p>version을 한 번 읽은 결과만 캐시.
 * Redis 오류는 DB 적재로 대체하고 로그에는 식별자와 측정값을 남기지 않음.
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

    /** 저장 후 version 증가. 실패 시 기존 캐시가 TTL까지 남을 수 있음. 저장 결과는 반환. */
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
            // version과 값을 함께 읽어 불확실한 version의 적재 방지.
            key = keyPrefix + recordKey + ":" + readVersion(recordKey) + ":" + range;
            hit = redisTemplate.opsForValue().get(key);
        } catch (RuntimeException e) {
            // 캐시 오류는 적재 함수로 대체. 적재 함수는 try 밖에서 실행.
            log.warn(
                    "집계 캐시 조회 실패 prefix={} type={}",
                    keyPrefix,
                    e.getClass().getSimpleName());
            return loader.get();
        }

        if (hit != null) {
            try {
                List<T> parsed = objectMapper.readValue(hit, type);
                // JSON null은 적중값으로 반환하지 않음.
                if (parsed != null) {
                    return parsed;
                }
            } catch (Exception e) {
                // 배포 사이에 캐시 형식이 바뀌어도 DB 적재로 대체.
            }

            log.warn("집계 캐시 값을 쓸 수 없어 비적중으로 처리 prefix={}", keyPrefix);
        }

        List<T> loaded = loader.get();
        put(key, loaded, ttl, keyPrefix);

        return loaded;
    }

    /**
     * version 키가 없으면 초기값으로 간주.
     * 손상된 값은 호출부에서 전체 fallback 처리.
     */
    private long readVersion(String recordKey) {
        String stored = redisTemplate.opsForValue().get(VERSION_PREFIX + recordKey);

        return stored == null ? INITIAL_VERSION : Long.parseLong(stored);
    }

    private void put(String key, Object value, Duration ttl, String keyPrefix) {
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
        } catch (Exception e) {
            // 저장 실패는 다음 조회가 DB를 다시 보는 것으로 끝남.
            log.warn(
                    "집계 캐시 저장 실패 prefix={} type={}",
                    keyPrefix,
                    e.getClass().getSimpleName());
        }
    }
}
