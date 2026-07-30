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

    /**
     * 대조, 폐기와 저장을 한 원자 단위로 묶는 스크립트.
     *
     * <p>키가 없으면 {@code GET}이 Lua {@code false}를 돌려주므로 문자열 비교가 실패하고 0으로
     * 끝남. 별도의 존재 검사가 필요하지 않음.
     */
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

    /**
     * 리프레시 토큰 교체.
     *
     * <p>자바에서 {@code GET}으로 대조한 뒤 {@code DEL}과 {@code SET}을 부르지 않음. 같은 토큰으로
     * 두 요청이 동시에 들어오면 양쪽이 대조를 통과해 리프레시 토큰이 하나에서 둘로 늘어남.
     * {@code GETDEL}로 좁힐 수도 있지만 그러면 폐기와 저장 사이에서 죽을 때 사용자가 세션을 잃음.
     *
     * <p>함정: Redis Cluster에서는 구·신 키의 {@code tokenId}가 달라 슬롯이 갈리고 스크립트가
     * CROSSSLOT으로 실패. 단일 노드 전제이며, 옮기려면 키에 해시 태그를 넣어야 함.
     *
     * @throws BusinessException 제시된 토큰이 저장된 값과 다르거나 이미 폐기됐을 때, 또는 교체를
     *     완료할 수 없을 때
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

        // 구 키가 없거나 저장된 해시가 다른 경우. 이미 회전됐거나 폐기된 토큰의 재사용.
        if (!SWAPPED.equals(swapped)) {
            throw new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_INVALID);
        }
    }

    /**
     * 리프레시 토큰 폐기.
     *
     * <p>키가 없어도 성공. 폐기의 목표 상태가 "그 토큰을 쓸 수 없음"이고 이미 달성돼 있으므로,
     * 재로그아웃이나 재발급 뒤 로그아웃을 오류로 만들지 않음.
     *
     * @throws BusinessException 폐기를 완료할 수 없을 때
     */
    public void revoke(RefreshTokenClaims claims) {
        try {
            redisTemplate.delete(key(claims.memberId(), claims.tokenId()));
        } catch (DataAccessException e) {
            // 성공을 반환하면 클라이언트는 폐기됐다고 믿지만 토큰은 TTL까지 재발급에 쓸 수 있음.
            throw new BusinessException(ErrorCode.AUTH_TOKEN_REVOKE_FAILED);
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
        return Sha256.hex(refreshToken);
    }
}
