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
     * 액세스 토큰은 자체 만료까지 허용.
     * 리프레시 토큰의 주체로 소유권 확인.
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
