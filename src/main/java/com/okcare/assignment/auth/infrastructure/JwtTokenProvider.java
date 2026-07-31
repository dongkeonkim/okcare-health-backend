package com.okcare.assignment.auth.infrastructure;

import com.okcare.assignment.auth.domain.IssuedTokens;
import com.okcare.assignment.auth.domain.RefreshTokenClaims;
import com.okcare.assignment.common.error.BusinessException;
import com.okcare.assignment.common.error.ErrorCode;
import com.okcare.assignment.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/**
 * 액세스·리프레시 토큰 발급.
 *
 * <p>TTL은 외부 응답 계약으로 고정하고 서명 키는 기동 시점에 검증.
 */
@Component
public class JwtTokenProvider {

    private static final Duration ACCESS_TOKEN_TTL = Duration.ofMinutes(15);
    private static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(14);

    /** HS256이 요구하는 최소 키 길이. */
    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey signingKey;
    private final Clock clock;

    public JwtTokenProvider(JwtProperties properties, Clock clock) {
        this.signingKey = toSigningKey(properties.secret());
        this.clock = clock;
    }

    /**
     * 토큰 쌍 발급.
     *
     * <p>두 토큰의 발급 시각을 동일하게 유지.
     */
    public IssuedTokens issue(long memberId) {
        String tokenId = UUID.randomUUID().toString();
        Instant issuedAt = clock.instant();
        String subject = Long.toString(memberId);

        return new IssuedTokens(
                tokenId,
                sign(subject, issuedAt, ACCESS_TOKEN_TTL, null),
                ACCESS_TOKEN_TTL.toSeconds(),
                sign(subject, issuedAt, REFRESH_TOKEN_TTL, tokenId),
                REFRESH_TOKEN_TTL.toSeconds());
    }

    /**
     * 리프레시 토큰 검증 후 저장 키를 가리키는 값 반환.
     *
     * @throws BusinessException 서명·만료 검증에 실패했거나 {@code jti}가 없을 때
     */
    public RefreshTokenClaims parseRefreshToken(String token) {
        Claims claims = parseClaims(token, ErrorCode.AUTH_REFRESH_TOKEN_INVALID);

        // jti가 없으면 액세스 토큰. 가리킬 저장 키가 없으므로 재발급 입력으로 받지 않음.
        if (claims.getId() == null) {
            throw new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_INVALID);
        }

        return new RefreshTokenClaims(
                memberId(claims, ErrorCode.AUTH_REFRESH_TOKEN_INVALID), claims.getId());
    }

    /**
     * 액세스 토큰 검증 후 회원 식별자 반환.
     *
     * @throws BusinessException 서명·만료 검증에 실패했거나 {@code jti}가 있을 때
     */
    public long parseAccessToken(String token) {
        Claims claims = parseClaims(token, ErrorCode.AUTH_ACCESS_TOKEN_INVALID);

        // 리프레시 토큰을 액세스 인증에 사용하는 경로 차단.
        if (claims.getId() != null) {
            throw new BusinessException(ErrorCode.AUTH_ACCESS_TOKEN_INVALID);
        }

        return memberId(claims, ErrorCode.AUTH_ACCESS_TOKEN_INVALID);
    }

    /**
     * 서명·만료 검증.
     *
     * <p>주입한 {@link Clock}을 사용하고 만료·위조는 같은 오류로 통합.
     */
    private Claims parseClaims(String token, ErrorCode onFailure) {
        try {
            return Jwts.parser()
                    .verifyWith(signingKey)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            throw new BusinessException(onFailure);
        }
    }

    /** {@code Long.parseLong}은 {@code null}에도 예외를 던지므로 subject 부재까지 함께 걸림. */
    private static long memberId(Claims claims, ErrorCode onFailure) {
        try {
            return Long.parseLong(claims.getSubject());
        } catch (NumberFormatException e) {
            throw new BusinessException(onFailure);
        }
    }

    /**
     * @param tokenId {@code null}이면 {@code jti}를 넣지 않음. 액세스 토큰은 폐기 대상이 아니라
     *     저장 키를 가리킬 필요가 없음.
     */
    private String sign(String subject, Instant issuedAt, Duration ttl, String tokenId) {
        JwtBuilder builder =
                Jwts.builder()
                        .subject(subject)
                        .issuedAt(Date.from(issuedAt))
                        .expiration(Date.from(issuedAt.plus(ttl)));

        // 라이브러리의 null claim 처리에 의존하지 않는 분기.
        if (tokenId != null) {
            builder.id(tokenId);
        }

        return builder.signWith(signingKey, Jwts.SIG.HS256).compact();
    }

    /**
     * 서명 키의 형식·최소 길이를 검증.
     * secret 자체는 오류에 포함하지 않음.
     */
    private static SecretKey toSigningKey(String base64Secret) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Secret);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "JWT_SECRET이 Base64 형식이 아니어서 기동할 수 없습니다."
                            + " .env.example을 참고해 Base64로 인코딩한 값을 넣으세요.");
        }

        if (decoded.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET은 Base64 디코딩 후 "
                            + MIN_SECRET_BYTES
                            + "바이트 이상이어야 하지만 "
                            + decoded.length
                            + "바이트입니다.");
        }

        return Keys.hmacShaKeyFor(decoded);
    }
}
