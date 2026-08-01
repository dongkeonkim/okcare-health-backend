package com.okcare.assignment.config;

import java.util.List;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

/**
 * 필수 외부 설정 누락 시 기동 실패 처리.
 *
 * <p>문자열 placeholder는 바인딩 단계에서 남을 수 있음.
 * 컨텍스트 생성 전에 이름과 JWT 기본값을 확인.
 * 환경변수 값은 로그나 예외에 남기지 않음.
 */
public class RequiredEnvironmentValidator
        implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    private static final List<String> REQUIRED_NAMES =
            List.of(
                    "DB_HOST",
                    "DB_PORT",
                    "DB_NAME",
                    "DB_USERNAME",
                    "DB_PASSWORD",
                    "REDIS_HOST",
                    "REDIS_PORT",
                    "JWT_SECRET");

    /** 공개 예시 키로 토큰을 서명하지 않도록 거부하는 placeholder. */
    private static final String JWT_SECRET_PLACEHOLDER = "CHANGE_ME";

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        validate(event.getEnvironment());
    }

    /** 누락된 설정이 있으면 {@link IllegalStateException} 발생. */
    public void validate(Environment environment) {
        List<String> missing =
                REQUIRED_NAMES.stream()
                        .filter(name -> !environment.containsProperty(name))
                        .toList();

        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "필수 설정이 없어 기동할 수 없습니다: "
                            + String.join(", ", missing)
                            + ". .env.example을 참고해 환경변수를 채우세요.");
        }

        if (JWT_SECRET_PLACEHOLDER.equals(environment.getProperty("JWT_SECRET"))) {
            throw new IllegalStateException(
                    "JWT_SECRET이 채워지지 않았습니다. openssl rand -base64 32로 생성한 값을 넣으세요.");
        }
    }
}
