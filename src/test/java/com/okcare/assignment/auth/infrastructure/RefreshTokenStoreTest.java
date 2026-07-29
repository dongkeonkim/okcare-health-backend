package com.okcare.assignment.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.mock;
import static org.mockito.BDDMockito.times;
import static org.mockito.BDDMockito.verify;
import static org.mockito.BDDMockito.willThrow;

import com.okcare.assignment.auth.domain.IssuedTokens;
import com.okcare.assignment.auth.domain.RefreshTokenClaims;
import com.okcare.assignment.common.error.BusinessException;
import com.okcare.assignment.common.error.ErrorCode;
import java.time.Duration;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

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

    private static final String NEW_REFRESH_TOKEN = "new.payload.signature";

    /** {@code NEW_REFRESH_TOKEN}의 SHA-256 hex. 위와 같은 이유로 따로 계산한 값. */
    private static final String EXPECTED_NEW_HASH =
            "f6427aa94c12b98d02ccbaa6c7fafde2ba40fbfc56aa2720add1117a9d25a1bc";

    private static final IssuedTokens NEW_TOKENS =
            new IssuedTokens("new-token-id", "new-access", 900, NEW_REFRESH_TOKEN, 1_209_600);

    private static final RefreshTokenClaims CLAIMS = new RefreshTokenClaims(7L, "token-id");

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

        assertThatFails(() -> store.save(7L, TOKENS), ErrorCode.AUTH_TOKEN_STORE_FAILED);
    }

    @Test
    @DisplayName("교체는 대조·폐기·저장을 한 스크립트에 담아 한 번만 실행한다")
    @SuppressWarnings("unchecked")
    void rotateRunsOneScriptCoveringAllThreeSteps() {
        givenScriptReturns(1L);

        store.rotate(CLAIMS, REFRESH_TOKEN, NEW_TOKENS);

        ArgumentCaptor<RedisScript<Long>> script = ArgumentCaptor.forClass(RedisScript.class);
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate, times(1))
                .execute(
                        script.capture(),
                        keys.capture(),
                        eq(EXPECTED_HASH),
                        eq(EXPECTED_NEW_HASH),
                        eq("1209600"));

        assertThat(keys.getValue())
                .containsExactly("auth:refresh:7:token-id", "auth:refresh:7:new-token-id");

        // 세 연산이 한 스크립트 안에 있어야 원자적. 대조를 자바로 끌어올리고 DEL·SET만 남기면
        // 같은 토큰으로 동시에 들어온 두 요청이 모두 통과함. 통합 테스트의 동시 요청은 스레드
        // 스케줄에 좌우되므로 그 회귀를 확실히 잡지 못하고, 구조를 고정하는 곳이 여기뿐임.
        assertThat(script.getValue().getScriptAsString())
                .contains("GET", "DEL", "SET", "EX")
                .contains("KEYS[1]", "KEYS[2]", "ARGV[1]", "ARGV[2]", "ARGV[3]");
    }

    @Test
    @DisplayName("스크립트가 교체하지 않았다고 알리면 폐기된 토큰으로 처리한다")
    void translatesRotateMismatch() {
        // 저장된 해시와 다르거나 키가 이미 사라진 경우. 회전된 토큰의 재사용이 도달하는 자리.
        givenScriptReturns(0L);

        assertThatFails(
                () -> store.rotate(CLAIMS, REFRESH_TOKEN, NEW_TOKENS),
                ErrorCode.AUTH_REFRESH_TOKEN_INVALID);
    }

    @Test
    @DisplayName("Redis 장애로 교체를 완료하지 못하면 로그인 저장 실패와 다른 코드로 바꾼다")
    @SuppressWarnings("unchecked")
    void translatesRotateFailure() {
        // 상태와 메시지가 같아도 코드를 나눠야 로그에서 로그인 저장 실패와 구분됨.
        willThrow(new RedisConnectionFailureException("연결 실패"))
                .given(redisTemplate)
                .execute(any(RedisScript.class), anyList(), any(), any(), any());

        assertThatFails(
                () -> store.rotate(CLAIMS, REFRESH_TOKEN, NEW_TOKENS),
                ErrorCode.AUTH_TOKEN_ROTATE_FAILED);
    }

    @Test
    @DisplayName("폐기는 회원과 tokenId로 만든 키만 삭제한다")
    void revokeDeletesOwnKey() {
        store.revoke(CLAIMS);

        verify(redisTemplate).delete("auth:refresh:7:token-id");
    }

    @Test
    @DisplayName("이미 없는 키를 폐기해도 오류로 만들지 않는다")
    void revokeIsIdempotent() {
        // delete는 지운 것이 없으면 false를 돌려줌. 그것을 실패로 읽으면 재로그아웃이 오류가 됨.
        given(redisTemplate.delete(anyString())).willReturn(false);

        assertThatCode(() -> store.revoke(CLAIMS)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Redis 장애로 폐기하지 못하면 재발급 실패와 다른 코드로 바꾼다")
    void translatesRevokeFailure() {
        // 성공을 반환하면 클라이언트는 폐기됐다고 믿지만 토큰은 TTL까지 재발급에 쓸 수 있음.
        willThrow(new RedisConnectionFailureException("연결 실패"))
                .given(redisTemplate)
                .delete(anyString());

        assertThatFails(() -> store.revoke(CLAIMS), ErrorCode.AUTH_TOKEN_REVOKE_FAILED);
    }

    @SuppressWarnings("unchecked")
    private void givenScriptReturns(Long result) {
        given(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any()))
                .willReturn(result);
    }

    private static void assertThatFails(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(expected);
    }
}
