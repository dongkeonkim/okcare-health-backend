package com.okcare.assignment.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.okcare.assignment.auth.domain.IssuedTokens;
import com.okcare.assignment.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 만료값은 기능 명세가 응답으로 노출하는 계약이고, {@code jti}는 리프레시 토큰 저장 키를 구성하므로
 * 둘 중 하나가 어긋나면 재발급과 로그아웃이 잘못된 키를 가리킴.
 */
class JwtTokenProviderTest {

    /** 32바이트. 안전 기준을 충족하는 최소 길이. */
    private static final String SECRET = base64("okcare-test-jwt-secret-32bytes!!");

    /** 31바이트. 경계에서 한 바이트 부족. */
    private static final String SHORT_SECRET = base64("okcare-test-jwt-secret-32bytes!");

    private static final Instant ISSUED_AT = Instant.parse("2024-12-17T01:23:45Z");

    private final JwtTokenProvider provider = providerWith(SECRET);

    @Test
    @DisplayName("액세스 토큰의 subject는 회원 식별자이고 만료는 발급 시각에서 15분 뒤다")
    void issuesAccessTokenValidFor15Minutes() {
        IssuedTokens tokens = provider.issue(42L);

        Claims claims = parse(tokens.accessToken());

        assertThat(claims.getSubject()).isEqualTo("42");
        assertThat(claims.getIssuedAt()).isEqualTo(Date.from(ISSUED_AT));
        assertThat(claims.getExpiration())
                .isEqualTo(Date.from(ISSUED_AT.plus(Duration.ofMinutes(15))));
        assertThat(tokens.accessTokenExpiresIn()).isEqualTo(900);
    }

    @Test
    @DisplayName("리프레시 토큰의 만료는 14일 뒤이고 jti는 tokenId와 같다")
    void issuesRefreshTokenCarryingTokenIdAsJti() {
        // 저장 키의 tokenId와 토큰 안의 jti가 다르면 재발급이 폐기할 키를 찾지 못함.
        IssuedTokens tokens = provider.issue(42L);

        Claims claims = parse(tokens.refreshToken());

        assertThat(claims.getSubject()).isEqualTo("42");
        assertThat(claims.getId()).isEqualTo(tokens.tokenId());
        assertThat(claims.getExpiration())
                .isEqualTo(Date.from(ISSUED_AT.plus(Duration.ofDays(14))));
        assertThat(tokens.refreshTokenExpiresIn()).isEqualTo(1_209_600);
    }

    @Test
    @DisplayName("발급마다 다른 tokenId를 만든다")
    void issuesDistinctTokenIdPerCall() {
        // 회원 식별자나 발급 시각에서 유도하면 같은 시각의 두 로그인이 한 키를 덮어써서
        // 먼저 로그인한 기기의 리프레시 토큰이 조용히 무효가 됨.
        assertThat(provider.issue(42L).tokenId()).isNotEqualTo(provider.issue(42L).tokenId());
    }

    @ParameterizedTest(name = "{0}바이트")
    @DisplayName("기준보다 긴 secret으로도 토큰을 발급한다")
    @ValueSource(ints = {32, 48, 64})
    void issuesTokensWithLongerSecret(int length) {
        // openssl rand -base64 64처럼 넉넉한 길이를 넣는 것이 오히려 흔한데, 길이에서 키 알고리즘을
        // 유도하는 구현이면 HS256 서명이 거부되어 첫 로그인에서야 500으로 드러남.
        JwtTokenProvider longSecretProvider = providerWith(base64("a".repeat(length)));

        assertThat(longSecretProvider.issue(42L).accessToken()).isNotBlank();
    }

    @Test
    @DisplayName("secret이 32바이트 미만이면 기동 시점에 실패한다")
    void rejectsSecretShorterThan256Bits() {
        assertThatThrownBy(() -> providerWith(SHORT_SECRET))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32바이트");
    }

    @Test
    @DisplayName("secret이 Base64 형식이 아니면 기동 시점에 실패한다")
    void rejectsNonBase64Secret() {
        // 환경변수가 없으면 placeholder가 이 리터럴로 바인딩되므로 실제로 도달하는 입력.
        assertThatThrownBy(() -> providerWith("${JWT_SECRET}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Base64");
    }

    @Test
    @DisplayName("설정 검증 실패 메시지에 secret 값을 담지 않는다")
    void neverEchoesSecretInFailureMessage() {
        assertThatThrownBy(() -> providerWith(SHORT_SECRET))
                .hasMessageNotContaining(SHORT_SECRET)
                .hasMessageNotContaining("okcare-test-jwt-secret");
    }

    private static JwtTokenProvider providerWith(String secret) {
        return new JwtTokenProvider(
                new JwtProperties(secret), Clock.fixed(ISSUED_AT, ZoneOffset.UTC));
    }

    /** 서명 검증까지 통과해야 파싱되므로 설정한 secret으로 서명했는지도 함께 확인. */
    private static Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(signingKey())
                // 과거 시각으로 발급한 토큰이라 실제 현재 시각으로는 만료로 판정됨.
                .clock(() -> Date.from(ISSUED_AT))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private static SecretKey signingKey() {
        return new SecretKeySpec(Base64.getDecoder().decode(SECRET), "HmacSHA256");
    }

    private static String base64(String raw) {
        return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
