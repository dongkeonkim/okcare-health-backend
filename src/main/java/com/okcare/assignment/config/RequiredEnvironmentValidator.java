package com.okcare.assignment.config;

import java.util.List;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

/**
 * 필수 외부 설정 누락 시 기동 실패 처리.
 *
 * <p>Spring Boot는 해석되지 않은 placeholder를 예외 없이 리터럴 문자열로 바인딩. 숫자 타입은
 * 바인딩 실패로 걸리지만 {@code REDIS_HOST}처럼 문자열 타입이 빠지면 기동이 성공하고 실제 연결
 * 시점에야 실패. 그 시점에는 원인이 설정 누락임을 알기 어려움.
 *
 * <p>{@link ApplicationEnvironmentPreparedEvent}는 컨텍스트 생성 이전에 발생하므로 DataSource가
 * 만들어지기 전에 멈출 수 있음. {@code EnvironmentPostProcessor}를 쓰지 않는 이유는 그 등록 방식이
 * {@code META-INF} 리소스에 의존하고, {@code bootJar}가 해당 파일을 실행 가능 jar의 클래스패스
 * 밖으로 옮겨 조용히 비활성화되기 때문. 여기서는 {@code main}에서 직접 등록.
 *
 * <p>이름 존재 여부만 확인하고 값은 읽지 않으므로 자격 증명이 로그나 예외 메시지에 남지 않음.
 */
public class RequiredEnvironmentValidator
        implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    /** application.yml의 placeholder가 의존하는 설정 이름. */
    private static final List<String> REQUIRED_NAMES =
            List.of(
                    "DB_HOST",
                    "DB_PORT",
                    "DB_NAME",
                    "DB_USERNAME",
                    "DB_PASSWORD",
                    "REDIS_HOST",
                    "REDIS_PORT");

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
    }
}
