package com.okcare.assignment.auth.application;

import com.okcare.assignment.auth.domain.RefreshTokenClaims;
import com.okcare.assignment.auth.infrastructure.JwtTokenProvider;
import com.okcare.assignment.auth.infrastructure.RefreshTokenStore;
import com.okcare.assignment.common.error.BusinessException;
import com.okcare.assignment.common.error.ErrorCode;
import org.springframework.stereotype.Service;

@Service
public class LogoutService {

    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenStore refreshTokenStore;

    public LogoutService(JwtTokenProvider jwtTokenProvider, RefreshTokenStore refreshTokenStore) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.refreshTokenStore = refreshTokenStore;
    }

    /**
     * 전달된 리프레시 토큰 폐기.
     *
     * <p>액세스 토큰은 폐기하지 않음. 기능 명세가 자체 만료 시각까지 사용할 수 있다고 정하므로
     * 블랙리스트를 두지 않음.
     *
     * <p>저장 키를 액세스 토큰이 아니라 리프레시 토큰의 주체로 만듦. 액세스 토큰 주체로 만들면 남의
     * 토큰을 제시했을 때 없는 키를 지우려 해 멱등 성공으로 조용히 넘어감.
     *
     * @param authenticatedMemberId 인증 필터가 세운 주체
     * @throws BusinessException 리프레시 토큰이 유효하지 않거나 인증된 회원의 것이 아닐 때, 폐기를
     *     완료할 수 없을 때
     */
    public void logout(long authenticatedMemberId, String refreshToken) {
        RefreshTokenClaims claims = jwtTokenProvider.parseRefreshToken(refreshToken);

        if (claims.memberId() != authenticatedMemberId) {
            throw new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_INVALID);
        }

        refreshTokenStore.revoke(claims);
    }
}
