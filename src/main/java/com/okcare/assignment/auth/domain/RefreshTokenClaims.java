package com.okcare.assignment.auth.domain;

/**
 * 검증을 통과한 리프레시 토큰에서 뽑은 값.
 *
 * <p>{@code tokenId}는 저장 키를 가리키는 {@code jti}. 이 값이 없는 토큰은 액세스 토큰이므로
 * 재발급 입력으로 받지 않음.
 */
public record RefreshTokenClaims(long memberId, String tokenId) {}
