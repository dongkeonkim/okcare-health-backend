package com.okcare.assignment.auth.api;

import com.okcare.assignment.auth.domain.IssuedTokens;

/**
 * 필드 이름과 순서는 기능 명세의 응답 계약.
 *
 * <p>로그인과 재발급이 같은 계약을 공유하므로 {@code LoginResponse}가 아님. 한쪽 이름을 쓰면 다른
 * 엔드포인트에서 전용 계약으로 오해함.
 */
public record TokenResponse(
        String tokenType,
        String accessToken,
        long accessTokenExpiresIn,
        String refreshToken,
        long refreshTokenExpiresIn) {

    private static final String BEARER = "Bearer";

    public static TokenResponse from(IssuedTokens tokens) {
        return new TokenResponse(
                BEARER,
                tokens.accessToken(),
                tokens.accessTokenExpiresIn(),
                tokens.refreshToken(),
                tokens.refreshTokenExpiresIn());
    }
}
