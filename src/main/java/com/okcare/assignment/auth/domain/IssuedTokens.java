package com.okcare.assignment.auth.domain;

/**
 * 한 번의 발급으로 만들어진 토큰 묶음.
 *
 * <p>{@code tokenId}는 리프레시 저장 키와 {@code jti}에 사용.
 */
public record IssuedTokens(
        String tokenId,
        String accessToken,
        long accessTokenExpiresIn,
        String refreshToken,
        long refreshTokenExpiresIn) {}
