package com.okcare.assignment.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.okcare.assignment.auth.application.TokenRefreshService;
import com.okcare.assignment.common.error.BusinessException;
import com.okcare.assignment.common.error.ErrorCode;
import io.jsonwebtoken.Jwts;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AuthRefreshIntegrationTest extends AuthIntegrationSupport {

    @Autowired private TokenRefreshService tokenRefreshService;

    @Test
    @DisplayName("재발급하면 기존 키가 사라지고 새 키가 14일 TTL로 생긴다")
    void rotatesStoredToken() throws Exception {
        long memberId = signup("rotate@example.com");
        String oldToken = loginSuccessfully("rotate@example.com").get("refreshToken").asText();

        String newToken = refreshTokenOf(refresh(oldToken));

        assertThat(jti(newToken)).isNotEqualTo(jti(oldToken));

        // 기존 키가 남아 있으면 회전이 아니라 발급만 한 것이고, 구 토큰이 계속 유효해짐.
        assertThat(redisTemplate.opsForValue().get(refreshTokenKey(memberId, jti(oldToken))))
                .isNull();

        String newKey = refreshTokenKey(memberId, jti(newToken));
        assertThat(redisTemplate.opsForValue().get(newKey)).isEqualTo(sha256Hex(newToken));
        assertThat(Duration.ofSeconds(redisTemplate.getExpire(newKey)))
                .isBetween(Duration.ofDays(13), Duration.ofDays(14));
    }

    @Test
    @DisplayName("회전된 리프레시 토큰을 다시 쓰면 401을 반환한다")
    void rejectsRotatedToken() throws Exception {
        signup("reuse@example.com");
        String oldToken = loginSuccessfully("reuse@example.com").get("refreshToken").asText();

        refresh(oldToken).andExpect(status().isOk());

        refresh(oldToken)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REFRESH_TOKEN_INVALID"));
    }

    @Test
    @DisplayName("키는 있지만 저장된 해시가 다르면 그 키를 지우지 않고 거부한다")
    void keepsStoredHashWhenTokenDiffers() throws Exception {
        // 다른 거부 사례는 모두 키가 이미 사라진 경로라 스크립트의 대조 분기를 지나지 않음.
        // 대조가 무력화되면 여기서 통과하고, 대조 전에 삭제가 일어나면 유효한 토큰이 사라짐.
        long memberId = signup("mismatch@example.com");
        String token = loginSuccessfully("mismatch@example.com").get("refreshToken").asText();

        String key = refreshTokenKey(memberId, jti(token));
        String otherHash = "0".repeat(64);
        redisTemplate.opsForValue().set(key, otherHash, Duration.ofDays(14));

        refresh(token)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REFRESH_TOKEN_INVALID"));

        // 값이 그대로이고 새 키도 생기지 않아야 부분 변경이 없었다는 뜻.
        assertThat(refreshKeysOf(memberId)).containsExactly(key);
        assertThat(redisTemplate.opsForValue().get(key)).isEqualTo(otherHash);
    }

    @Test
    @DisplayName("재발급한 토큰으로 다시 재발급할 수 있다")
    void allowsChainedRefresh() throws Exception {
        signup("chain@example.com");
        String first = loginSuccessfully("chain@example.com").get("refreshToken").asText();

        String second = refreshTokenOf(refresh(first));

        refresh(second).andExpect(status().isOk());
    }

    @Test
    @DisplayName("다른 secret으로 서명된 토큰과 폐기된 토큰의 401 응답이 서로 구별되지 않는다")
    void hidesRejectionReason() throws Exception {
        // 이유를 나눠 응답하면 그 토큰이 한때 유효했는지 알려 주게 됨.
        signup("forged@example.com");
        String valid = loginSuccessfully("forged@example.com").get("refreshToken").asText();
        refresh(valid).andExpect(status().isOk());

        String revoked =
                failureBodyWithoutTrace(
                        refresh(valid).andExpect(status().isUnauthorized()));
        String forged =
                failureBodyWithoutTrace(
                        refresh(forgedToken()).andExpect(status().isUnauthorized()));

        assertThat(revoked).isEqualTo(forged);
    }

    @Test
    @DisplayName("같은 리프레시 토큰으로 동시에 재발급하면 한 건만 성공하고 키가 늘지 않는다")
    void allowsOnlyOneOfConcurrentRefreshes() throws Exception {
        // 실제 Redis에서 동시 요청의 관찰 결과를 고정. 래치는 시작만 맞추고 대조와 변경 사이를
        // 겹치게 강제하지 못하므로, 비원자 구현으로의 회귀를 이 테스트만으로 단정할 수 없음.
        // 세 연산이 한 스크립트에 있다는 구조는 RefreshTokenStoreTest가 고정함.
        long memberId = signup("concurrent@example.com");
        String token = loginSuccessfully("concurrent@example.com").get("refreshToken").asText();

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> first = pool.submit(refreshTask(start, token));
            Future<Throwable> second = pool.submit(refreshTask(start, token));
            start.countDown();

            List<Throwable> outcomes =
                    Arrays.asList(
                            first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));

            long succeeded = outcomes.stream().filter(Objects::isNull).count();
            long rejected =
                    outcomes.stream()
                            .filter(BusinessException.class::isInstance)
                            .map(BusinessException.class::cast)
                            .filter(e -> e.errorCode() == ErrorCode.AUTH_REFRESH_TOKEN_INVALID)
                            .count();

            assertThat(succeeded).isEqualTo(1);
            assertThat(rejected).isEqualTo(1);
            assertThat(refreshKeysOf(memberId)).hasSize(1);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    /** 예외를 던지지 않고 반환. 스레드 안에서 터지면 성공·거절 건수를 셀 수 없음. */
    private Callable<Throwable> refreshTask(CountDownLatch start, String token) {
        return () -> {
            start.await();
            try {
                tokenRefreshService.refresh(token);
                return null;
            } catch (Throwable t) {
                return t;
            }
        };
    }

    /** 운영 코드는 KEYS를 쓰지 않음. 토큰이 늘지 않았음을 보이려면 개수를 세는 것이 유일한 방법. */
    private Set<String> refreshKeysOf(long memberId) {
        return redisTemplate.keys(refreshTokenKey(memberId, "*"));
    }

    /** 서명만 다른 토큰. 구조와 클레임이 유효하므로 서명 검증 외의 이유로 거부될 여지가 없음. */
    private static String forgedToken() {
        SecretKeySpec otherKey =
                new SecretKeySpec("another-secret-that-is-32bytes!!".getBytes(), "HmacSHA256");

        return Jwts.builder()
                .subject("1")
                .id("forged-token-id")
                .expiration(Date.from(Instant.now().plus(Duration.ofDays(14))))
                .signWith(otherKey, Jwts.SIG.HS256)
                .compact();
    }
}
