package com.okcare.assignment.common.error;

import org.springframework.http.HttpStatus;

/** 메시지에 입력값을 섞지 않음. 오류 응답으로 개인 식별 정보가 새어 나감. */
public enum ErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    // 존재하지 않는 회원과 잘못된 비밀번호를 같은 응답으로 처리.
    AUTH_CREDENTIALS_INVALID(
            HttpStatus.UNAUTHORIZED,
            "이메일 또는 비밀번호가 올바르지 않습니다."),
    // 토큰·헤더 오류의 외부 응답 통합. 액세스·리프레시 로그 분리.
    AUTH_ACCESS_TOKEN_INVALID(
            HttpStatus.UNAUTHORIZED,
            "인증이 필요합니다."),
    AUTH_REFRESH_TOKEN_INVALID(
            HttpStatus.UNAUTHORIZED,
            "다시 로그인해 주세요."),
    // 공급자·단위·시각 파싱 오류를 요청 오류로 통합.
    HEALTH_DATA_INVALID(HttpStatus.BAD_REQUEST, "건강 데이터 형식이 올바르지 않습니다."),
    // 없는 recordkey와 타인 소유 recordkey를 같은 404로 처리해 존재 여부 은닉.
    HEALTH_RECORD_KEY_NOT_FOUND(HttpStatus.NOT_FOUND, "조회할 수 없는 recordkey입니다."),
    MEMBER_EMAIL_DUPLICATED(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다."),
    HEALTH_RECORD_KEY_CONFLICT(HttpStatus.CONFLICT, "다른 회원이 사용 중인 recordkey입니다."),
    // 외부 메시지는 저장소 비노출. 저장·교체·폐기 단계별 로그 구분.
    AUTH_TOKEN_STORE_FAILED(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "로그인을 완료하지 못했습니다. 잠시 후 다시 시도해 주세요."),
    AUTH_TOKEN_ROTATE_FAILED(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "토큰 재발급을 완료하지 못했습니다. 잠시 후 다시 시도해 주세요."),
    AUTH_TOKEN_REVOKE_FAILED(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "로그아웃을 완료하지 못했습니다. 잠시 후 다시 시도해 주세요."),
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
