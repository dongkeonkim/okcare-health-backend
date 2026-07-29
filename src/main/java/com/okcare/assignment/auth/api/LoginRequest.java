package com.okcare.assignment.auth.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 로그인 요청.
 *
 * <p>이메일 형식과 비밀번호 구성을 검증하지 않음. 가입 규칙을 강화하면 그 전에 만든 계정이 400으로
 * 막히고, 400과 401의 차이가 이메일 형식은 유효하다는 정보를 흘림. 자격 증명 오류는 전부 401.
 *
 * <p>{@code SignupRequest}와 달리 공백을 미리 다듬지 않음. 붙여 둘 {@code @Email} 제약이 없고
 * 조회 직전 정규화가 같은 일을 함.
 */
public record LoginRequest(
        @NotBlank(message = "이메일은 필수입니다.")
        String email,

        @NotBlank(message = "비밀번호는 필수입니다.")
        String password
) {}
