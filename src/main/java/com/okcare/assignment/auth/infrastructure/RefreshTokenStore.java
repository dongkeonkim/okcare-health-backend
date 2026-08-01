package com.okcare.assignment.auth.infrastructure;

import com.okcare.assignment.auth.domain.IssuedTokens;
import com.okcare.assignment.auth.domain.RefreshTokenClaims;
import com.okcare.assignment.common.error.BusinessException;
import com.okcare.assignment.common.error.ErrorCode;
import com.okcare.assignment.common.security.Sha256;
import java.time.Duration;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/** 리프레시 토큰 저장소. 평문이 아니라 SHA-256 해시만 보관. */
@Component
public class RefreshTokenStore {

    private static final String KEY_PREFIX = "auth:refresh:";

    /** 대조·폐기·저장을 원자 단위로 묶는 스크립트. */
    private static final RedisScript<Long> ROTATE_SCRIPT =
            RedisScript.of(
                    """
                    if redis.call('GET', KEYS[1]) ~= ARGV[1] then
                      return 0
                    end
                    redis.call('DEL', KEYS[1])
                    redis.call('SET', KEYS[2], ARGV[2], 'EX', ARGV[3])
                    return 1
                    """,
                    Long.class);

    private static final Long SWAPPED = 1L;

    private final StringRedisTemplate redisTemplate;

    public RefreshTokenStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 발급한 리프레시 토큰의 해시 저장.
     *
     * <p>TTL은 토큰 만료값과 동일하게 유지.
     */
    public void save(long memberId, IssuedTokens tokens) {
        String key = key(memberId, tokens.tokenId());
        Duration ttl = Duration.ofSeconds(tokens.refreshTokenExpiresIn());

        try {
            redisTemplate.opsForValue().set(key, hash(tokens.refreshToken()), ttl);
        } catch (DataAccessException e) {
            // 저장 실패 시 로그인도 실패 처리.
            throw new BusinessException(ErrorCode.AUTH_TOKEN_STORE_FAILED);
        }
    }

    /**
     * 리프레시 토큰 원자적 교체.
     *
     * <p>동시 요청의 이중 회전 방지. Lua로 대조·폐기·저장을 원자화.
     * 단일 Redis 노드 전제. 클러스터 사용 시 두 키의 슬롯 조정 필요.
     *
     * @throws BusinessException 저장값과 토큰이 다르거나 교체에 실패한 경우
     */
    public void rotate(RefreshTokenClaims claims, String presentedToken, IssuedTokens newTokens) {
        Long swapped;

        try {
            swapped =
                    redisTemplate.execute(
                            ROTATE_SCRIPT,
                            List.of(
                                    key(claims.memberId(), claims.tokenId()),
                                    key(claims.memberId(), newTokens.tokenId())),
                            hash(presentedToken),
                            hash(newTokens.refreshToken()),
                            Long.toString(newTokens.refreshTokenExpiresIn()));
        } catch (DataAccessException e) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_ROTATE_FAILED);
        }

        // 기존 토큰 키가 없거나 저장된 해시가 다른 경우. 회전됐거나 폐기된 토큰의 재사용.
        if (!SWAPPED.equals(swapped)) {
            throw new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_INVALID);
        }
    }

    /** 이미 폐기된 토큰도 성공으로 취급해 로그아웃 멱등성 보장. */
    public void revoke(RefreshTokenClaims claims) {
        try {
            redisTemplate.delete(key(claims.memberId(), claims.tokenId()));
        } catch (DataAccessException e) {
            // 성공을 반환하면 폐기되지 않은 토큰이 TTL까지 재발급에 사용됨.
            throw new BusinessException(ErrorCode.AUTH_TOKEN_REVOKE_FAILED);
        }
    }

    static String key(long memberId, String tokenId) {
        return KEY_PREFIX + memberId + ":" + tokenId;
    }

    /** 평문 리프레시 토큰을 Redis에 남기지 않기 위한 SHA-256 단방향 변환. */
    static String hash(String refreshToken) {
        return Sha256.hex(refreshToken);
    }
}
