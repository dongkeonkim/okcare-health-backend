package com.okcare.assignment.auth.api;

import com.okcare.assignment.auth.domain.IssuedTokens;

/** 필드 이름과 순서는 기능 명세의 응답 계약. */
public record LoginResponse(
        String tokenType,
        String accessToken,
        long accessTokenExpiresIn,
        String refreshToken,
        long refreshTokenExpiresIn) {

    private static final String BEARER = "Bearer";

    public static LoginResponse from(IssuedTokens tokens) {
        return new LoginResponse(
                BEARER,
                tokens.accessToken(),
                tokens.accessTokenExpiresIn(),
                tokens.refreshToken(),
                tokens.refreshTokenExpiresIn());
    }
}
