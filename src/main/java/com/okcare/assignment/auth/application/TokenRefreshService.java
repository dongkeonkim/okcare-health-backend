package com.okcare.assignment.auth.application;

import com.okcare.assignment.auth.domain.IssuedTokens;
import com.okcare.assignment.auth.domain.RefreshTokenClaims;
import com.okcare.assignment.auth.infrastructure.JwtTokenProvider;
import com.okcare.assignment.auth.infrastructure.RefreshTokenStore;
import com.okcare.assignment.common.error.BusinessException;
import org.springframework.stereotype.Service;

@Service
public class TokenRefreshService {

    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenStore refreshTokenStore;

    public TokenRefreshService(
            JwtTokenProvider jwtTokenProvider, RefreshTokenStore refreshTokenStore) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.refreshTokenStore = refreshTokenStore;
    }

    /**
     * 리프레시 토큰을 새 토큰 쌍으로 교체.
     *
     * <p>MySQL을 조회하지 않음. 회원 삭제 기능이 없어 토큰이 가리키는 회원이 사라지는 상태가
     * 만들어지지 않으므로, 존재 확인을 넣어도 걸러낼 것이 없음.
     *
     * <p>교체 전에 새 토큰을 발급하는 이유는 스크립트가 새 토큰의 해시를 인자로 받아야 하기 때문.
     * 교체가 실패하면 발급한 토큰은 어디에도 저장되지 않고 버려짐.
     *
     * @throws BusinessException 토큰이 유효하지 않거나 이미 폐기됐을 때, 교체를 완료할 수 없을 때
     */
    public IssuedTokens refresh(String presentedToken) {
        RefreshTokenClaims claims = jwtTokenProvider.parseRefreshToken(presentedToken);
        IssuedTokens newTokens = jwtTokenProvider.issue(claims.memberId());

        refreshTokenStore.rotate(claims, presentedToken, newTokens);

        return newTokens;
    }
}
