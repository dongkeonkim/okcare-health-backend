package com.okcare.assignment.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * 필수 설정 누락 시 기동 실패 여부 검증.
 *
 * <p>판단 규칙만 확인. {@code main}에서 리스너로 등록되어 실제로 기동을 막는지는 실행 jar로
 * 확인해야 함. 등록이 빠져도 이 테스트는 통과함.
 */
class RequiredEnvironmentValidatorTest {

    private final RequiredEnvironmentValidator validator = new RequiredEnvironmentValidator();

    @Test
    @DisplayName("필수 설정이 모두 있으면 통과한다")
    void passesWhenAllPresent() {
        assertThatCode(() -> validator.validate(environmentWithAll())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("문자열 설정 REDIS_HOST가 빠지면 기동을 실패시킨다")
    void failsWhenStringPropertyMissing() {
        // Spring Boot 단독으로는 막지 못하는 경우다. REDIS_PORT(int)는 바인딩 실패로 걸리지만
        // REDIS_HOST(String)는 리터럴 "${REDIS_HOST}"가 그대로 바인딩되어 기동이 성공해버림.
        MockEnvironment environment =
                new MockEnvironment()
                        .withProperty("DB_HOST", "localhost")
                        .withProperty("DB_PORT", "3306")
                        .withProperty("DB_NAME", "okcare")
                        .withProperty("DB_USERNAME", "okcare")
                        .withProperty("DB_PASSWORD", "secret")
                        .withProperty("REDIS_PORT", "6379");

        assertThatThrownBy(() -> validator.validate(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REDIS_HOST");
    }

    @Test
    @DisplayName("실패 메시지에 설정 값을 담지 않는다")
    void doesNotLeakValues() {
        MockEnvironment environment =
                new MockEnvironment().withProperty("DB_PASSWORD", "top-secret");

        assertThatThrownBy(() -> validator.validate(environment))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(thrown -> assertThat(thrown.getMessage()).doesNotContain("top-secret"));
    }

    private MockEnvironment environmentWithAll() {
        return new MockEnvironment()
                .withProperty("DB_HOST", "localhost")
                .withProperty("DB_PORT", "3306")
                .withProperty("DB_NAME", "okcare")
                .withProperty("DB_USERNAME", "okcare")
                .withProperty("DB_PASSWORD", "secret")
                .withProperty("REDIS_HOST", "localhost")
                .withProperty("REDIS_PORT", "6379");
    }
}
