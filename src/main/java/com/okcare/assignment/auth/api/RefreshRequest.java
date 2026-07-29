package com.okcare.assignment.auth.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 토큰 재발급 요청.
 *
 * <p>액세스 토큰을 받지 않음. 액세스 토큰이 이미 만료된 상태에서 부르는 API라 그것을 요구하면
 * 재발급 자체가 불가능해짐.
 */
public record RefreshRequest(
        @NotBlank(message = "리프레시 토큰은 필수입니다.")
        String refreshToken
) {}
