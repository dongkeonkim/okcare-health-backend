package com.okcare.assignment.auth.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 길이 상한은 members 테이블의 컬럼 정의와 같은 값. */
public record SignupRequest(
        @NotBlank(message = "이름은 필수입니다.")
        @Size(max = 50, message = "이름은 50자를 넘을 수 없습니다.")
        String name,

        @NotBlank(message = "닉네임은 필수입니다.")
        @Size(max = 50, message = "닉네임은 50자를 넘을 수 없습니다.")
        String nickname,

        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        @Size(max = 255, message = "이메일은 255자를 넘을 수 없습니다.")
        String email,

        @NotBlank(message = "비밀번호는 필수입니다.")
        @Size(min = 8, max = 64, message = "비밀번호는 8자 이상 64자 이하여야 합니다.")
        @Pattern(
                regexp = "^(?=.*[A-Za-z])(?=.*[0-9]).+$",
                message = "비밀번호는 영문자와 숫자를 각각 하나 이상 포함해야 합니다.")
        String password
) {

    /**
     * 검증 전 이메일 공백 제거.
     *
     * <p>{@link Email} 제약은 공백이 붙은 값을 형식 위반으로 판정. 여기서 먼저 다듬지 않으면
     * 정규화 대상으로 허용한 입력이 400으로 거절됨.
     */
    public SignupRequest {
        if (email != null) {
            email = email.trim();
        }
    }
}
