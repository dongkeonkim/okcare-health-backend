package com.okcare.assignment.auth.api;

import com.okcare.assignment.auth.domain.IssuedTokens;

/** 로그인과 재발급이 공유하는 토큰 응답 계약. */
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
