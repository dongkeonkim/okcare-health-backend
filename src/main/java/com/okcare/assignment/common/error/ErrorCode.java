package com.okcare.assignment.common.error;

import org.springframework.http.HttpStatus;

/** 메시지에 입력값을 섞지 않음. 오류 응답으로 개인 식별 정보가 새어 나감. */
public enum ErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    // 회원 없음과 비밀번호 불일치가 같은 코드와 메시지를 공유. 나누면 이메일 등록 여부를
    // 확인하는 수단이 됨.
    AUTH_CREDENTIALS_INVALID(
            HttpStatus.UNAUTHORIZED,
            "이메일 또는 비밀번호가 올바르지 않습니다."),
    MEMBER_EMAIL_DUPLICATED(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다."),
    // 메시지가 실패한 저장소를 가리키지 않음. 내부 구성을 알려 줄 이유가 없음.
    AUTH_TOKEN_STORE_FAILED(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "로그인을 완료하지 못했습니다. 잠시 후 다시 시도해 주세요."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "요청을 처리하지 못했습니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    public String message() {
        return message;
    }
}
