package com.okcare.assignment.auth.domain;

/**
 * 한 번의 발급으로 만들어진 토큰 묶음.
 *
 * <p>{@code tokenId}는 리프레시 토큰의 {@code jti} 클레임과 같은 값이며 저장 키를 구성함. 응답
 * DTO로 옮기지 않음.
 *
 * <p>만료값이 초 단위인 것은 기능 명세의 응답 계약. {@code Duration}으로 두면 직렬화 형식이
 * 계약과 달라짐.
 */
public record IssuedTokens(
        String tokenId,
        String accessToken,
        long accessTokenExpiresIn,
        String refreshToken,
        long refreshTokenExpiresIn) {}
