package com.okcare.assignment.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.mock;
import static org.mockito.BDDMockito.verify;
import static org.mockito.BDDMockito.willThrow;

import com.okcare.assignment.auth.domain.IssuedTokens;
import com.okcare.assignment.common.error.BusinessException;
import com.okcare.assignment.common.error.ErrorCode;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * Redis를 대역으로 두는 이유는 저장 형식과 장애 변환만 확인하기 때문. 실제 TTL 동작과 키 존재는
 * 통합 테스트가 담당.
 */
class RefreshTokenStoreTest {

    private static final String REFRESH_TOKEN = "header.payload.signature";

    /**
     * {@code REFRESH_TOKEN}의 SHA-256 hex.
     *
     * <p>운영 코드를 다시 호출해 기대값을 만들지 않음. 같은 메서드로 기대값을 만들면 해시가 다른
     * 256비트 변환으로 바뀌어도 테스트가 함께 통과함.
     */
    private static final String EXPECTED_HASH =
            "256d04db4e5e4ac308751ed0885b722b758630567c53a7125ed9fbd068e5c3f6";

    private static final IssuedTokens TOKENS =
            new IssuedTokens("token-id", "access", 900, REFRESH_TOKEN, 1_209_600);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

    private final RefreshTokenStore store = new RefreshTokenStore(redisTemplate);

    @Test
    @DisplayName("평문이 아닌 SHA-256 hex를 개발 계획의 키 형식과 토큰 만료값 TTL로 저장한다")
    void storesHashedTokenUnderMemberAndTokenIdKey() {
        // 키 형식이 어긋나면 재발급과 로그아웃이 폐기할 대상을 찾지 못하고, 평문이 저장되면
        // Redis 덤프만으로 남의 계정 토큰을 그대로 쓸 수 있음.
        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        store.save(7L, TOKENS);

        verify(valueOperations)
                .set("auth:refresh:7:token-id", EXPECTED_HASH, Duration.ofDays(14));
    }

    @Test
    @DisplayName("Redis에 저장하지 못하면 로그인을 실패시키는 오류로 바꾼다")
    void translatesStoreFailure() {
        // 저장에 실패한 토큰을 반환하면 클라이언트는 14일 동안 유효하다고 믿지만 첫 재발급에서
        // 거절됨. 원인이 Redis 장애라는 사실도 응답으로 알려 주지 않아야 함.
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        willThrow(new RedisConnectionFailureException("연결 실패"))
                .given(valueOperations)
                .set(anyString(), anyString(), any(Duration.class));

        assertThatThrownBy(() -> store.save(7L, TOKENS))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.AUTH_TOKEN_STORE_FAILED);
    }
}
