package com.okcare.assignment.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.okcare.assignment.IntegrationSupport;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AuthLoginIntegrationTest extends IntegrationSupport {

    @Test
    @DisplayName("로그인하면 리프레시 토큰의 해시가 jti를 키로 14일 TTL로 저장된다")
    void storesHashedRefreshTokenWithTtl() throws Exception {
        long memberId = signup("store@example.com");

        String refreshToken = loginSuccessfully("store@example.com").get("refreshToken").asText();
        String key = refreshTokenKey(memberId, jti(refreshToken));

        // 평문이 저장되면 Redis 덤프만으로 남의 계정 토큰을 그대로 재발급에 쓸 수 있음. 기대값은
        // 운영 코드를 부르지 않고 따로 계산해, 다른 256비트 변환으로 바뀌면 드러나게 함.
        assertThat(redisTemplate.opsForValue().get(key)).isEqualTo(sha256Hex(refreshToken));

        // TTL이 없으면 폐기하지 않은 토큰이 영구히 남고, 14일을 넘으면 명세보다 오래 살아남음.
        assertThat(Duration.ofSeconds(redisTemplate.getExpire(key)))
                .isBetween(Duration.ofDays(13), Duration.ofDays(14));
    }

    @Test
    @DisplayName("대소문자와 앞뒤 공백이 다른 이메일로도 로그인할 수 있다")
    void acceptsNonNormalizedEmail() throws Exception {
        // 저장된 값이 정규화 형태이므로 조회 경로가 같은 규칙을 빠뜨리면 가입한 계정으로
        // 로그인할 수 없게 됨.
        signup("variant@example.com");

        login("  Variant@Example.COM  ", PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("등록되지 않은 이메일과 틀린 비밀번호의 401 응답이 서로 구별되지 않는다")
    void hidesWhetherEmailExists() throws Exception {
        // 두 응답이 조금이라도 다르면 로그인 화면이 가입 이메일을 확인하는 수단이 됨.
        signup("known@example.com");

        String unknownEmail =
                failureBodyWithoutTrace(
                        login("unknown@example.com", PASSWORD)
                                .andExpect(status().isUnauthorized()));
        String wrongPassword =
                failureBodyWithoutTrace(
                        login("known@example.com", "WrongPassword9")
                                .andExpect(status().isUnauthorized()));

        assertThat(unknownEmail).isEqualTo(wrongPassword);
    }

    @Test
    @DisplayName("두 번 로그인하면 두 리프레시 토큰이 서로 다른 키로 공존한다")
    void keepsPreviousRefreshTokenOnSecondLogin() throws Exception {
        // 로그인이 기존 토큰을 폐기하지 않는다는 정책. 키가 회원 식별자만으로 구성되면 나중
        // 로그인이 앞선 기기의 토큰을 조용히 무효로 만듦.
        long memberId = signup("multi@example.com");

        String first = jti(loginSuccessfully("multi@example.com").get("refreshToken").asText());
        String second = jti(loginSuccessfully("multi@example.com").get("refreshToken").asText());

        assertThat(first).isNotEqualTo(second);
        assertThat(redisTemplate.opsForValue().get(refreshTokenKey(memberId, first))).isNotNull();
        assertThat(redisTemplate.opsForValue().get(refreshTokenKey(memberId, second))).isNotNull();
    }
}
