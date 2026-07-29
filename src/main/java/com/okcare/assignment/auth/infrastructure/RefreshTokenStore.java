package com.okcare.assignment.auth.infrastructure;

import com.okcare.assignment.auth.domain.IssuedTokens;
import com.okcare.assignment.common.error.BusinessException;
import com.okcare.assignment.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** 리프레시 토큰 저장소. 평문이 아니라 SHA-256 해시만 보관. */
@Component
public class RefreshTokenStore {

    private static final String KEY_PREFIX = "auth:refresh:";

    private final StringRedisTemplate redisTemplate;

    public RefreshTokenStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 발급한 리프레시 토큰의 해시 저장.
     *
     * <p>TTL을 상수로 두지 않고 토큰 자신의 만료값에서 끌어옴. 두 값을 따로 관리하면 저장소에는
     * 남아 있지만 서명 검증에서 만료된 토큰, 또는 그 반대가 생김.
     *
     * @throws BusinessException 저장에 실패했을 때. 호출부가 로그인을 실패 처리해야 함
     */
    public void save(long memberId, IssuedTokens tokens) {
        String key = key(memberId, tokens.tokenId());
        Duration ttl = Duration.ofSeconds(tokens.refreshTokenExpiresIn());

        try {
            redisTemplate.opsForValue().set(key, hash(tokens.refreshToken()), ttl);
        } catch (DataAccessException e) {
            // 저장하지 못한 토큰을 반환하면 클라이언트는 14일 동안 쓸 수 있다고 믿지만 첫
            // 재발급에서 거절됨. 그 상태를 만들지 않기 위해 로그인 자체를 실패시킴.
            throw new BusinessException(ErrorCode.AUTH_TOKEN_STORE_FAILED);
        }
    }

    static String key(long memberId, String tokenId) {
        return KEY_PREFIX + memberId + ":" + tokenId;
    }

    /**
     * 저장용 단방향 변환.
     *
     * <p>비밀번호와 달리 salt와 반복 해시를 쓰지 않음. 대상이 사람이 고른 문자열이 아니라 서명된
     * 고엔트로피 토큰이라 사전 공격 대상이 아니고, 재발급마다 전달된 토큰으로 같은 해시를 다시
     * 계산해 대조해야 함.
     */
    static String hash(String refreshToken) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(refreshToken.getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256은 모든 JDK가 제공하도록 규격이 요구하므로 도달할 수 없음.
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }
}
