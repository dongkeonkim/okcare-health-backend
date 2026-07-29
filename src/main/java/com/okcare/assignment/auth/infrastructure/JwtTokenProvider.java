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
 * <p>유효시간을 설정이 아니라 상수로 둠. 기능 명세가 응답 필드로 노출하는 계약값이라 배포 없이
 * 바꿀 대상이 아니고, 설정으로 열면 문서와 실제 토큰이 어긋난 채로 기동함.
 *
 * <p>서명 키를 생성자에서 만드는 이유는 안전 기준 위반을 기동 시점에 실패시키기 위함. 발급
 * 시점으로 미루면 첫 로그인 요청에서야 500으로 드러남.
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
     * 회원 한 명에 대한 토큰 쌍 발급.
     *
     * <p>두 토큰의 {@code iat}를 한 번 읽은 같은 시각으로 맞춤. 각자 시각을 읽으면 만료값이
     * 요청 처리 시간만큼 어긋남.
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
     * <p>{@code parseSignedClaims}가 {@code alg: none} 거부 수단. 서명 없는 토큰은 JWS가 아니라
     * 이 호출에서 걸림. 서명을 요구하지 않는 파싱 메서드로 바꾸면 위조 토큰이 통과.
     *
     * <p>파서에도 발급과 같은 {@link Clock}을 넘김. 시스템 시각을 쓰면 만료 검증을 테스트에서
     * 고정할 수 없음.
     *
     * @throws BusinessException 서명·만료 검증에 실패했거나 {@code jti}가 없을 때
     */
    public RefreshTokenClaims parseRefreshToken(String token) {
        try {
            Claims claims =
                    Jwts.parser()
                            .verifyWith(signingKey)
                            .clock(() -> Date.from(clock.instant()))
                            .build()
                            .parseSignedClaims(token)
                            .getPayload();

            // jti가 없으면 액세스 토큰. 가리킬 저장 키가 없으므로 재발급 입력으로 받지 않음.
            if (claims.getId() == null) {
                throw new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_INVALID);
            }

            // parseLong의 NumberFormatException은 IllegalArgumentException이라 아래 catch가
            // 함께 받음. subject를 읽을 수 없는 토큰도 쓸 수 없는 토큰.
            return new RefreshTokenClaims(Long.parseLong(claims.getSubject()), claims.getId());
        } catch (JwtException | IllegalArgumentException e) {
            // 예외 종류를 오류 코드로 옮기지 않음. 만료와 위조를 구분해 응답하면 그 토큰이 한때
            // 유효했는지 알려 주게 됨.
            throw new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_INVALID);
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

        // 빌더에 null을 넘겼을 때 클레임을 지우는지 그대로 담는지가 라이브러리 구현에 달려
        // 있어 호출 자체를 건너뜀.
        if (tokenId != null) {
            builder.id(tokenId);
        }

        return builder.signWith(signingKey, Jwts.SIG.HS256).compact();
    }

    /**
     * 안전 기준을 검사한 서명 키 생성.
     *
     * <p>길이 검사를 {@code Keys.hmacShaKeyFor}의 예외에 맡기지 않음. 그 메시지는 영어로 비트 수만
     * 알려 주고 어느 설정을 어떻게 고쳐야 하는지 가리키지 않음.
     *
     * <p>어느 실패 경로에서도 secret 값을 메시지에 담지 않음. 기동 실패 로그로 새어 나감.
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
