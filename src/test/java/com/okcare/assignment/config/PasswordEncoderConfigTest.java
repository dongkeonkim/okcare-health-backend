package com.okcare.assignment.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class PasswordEncoderConfigTest {

    private static final String RAW_PASSWORD = "StrongPassword1";

    private final PasswordEncoder passwordEncoder = new PasswordEncoderConfig().passwordEncoder();

    @Test
    @DisplayName("평문을 그대로 저장하지 않는다")
    void doesNotStoreRawPassword() {
        assertThat(passwordEncoder.encode(RAW_PASSWORD)).isNotEqualTo(RAW_PASSWORD);
    }

    @Test
    @DisplayName("BCrypt 알고리즘 식별자를 함께 기록한다")
    void recordsAlgorithmIdentifier() {
        // 접두사가 없으면 나중에 알고리즘을 바꿀 때 기존 해시를 검증할 방법이 사라짐.
        assertThat(passwordEncoder.encode(RAW_PASSWORD)).startsWith("{bcrypt}$2a$");
    }

    @Test
    @DisplayName("같은 평문도 매번 다른 해시를 만든다")
    void producesDifferentHashForSameInput() {
        // salt가 없으면 해시 비교만으로 같은 비밀번호를 쓰는 회원을 골라낼 수 있음.
        assertThat(passwordEncoder.encode(RAW_PASSWORD))
                .isNotEqualTo(passwordEncoder.encode(RAW_PASSWORD));
    }

    @Test
    @DisplayName("해시는 원래 평문으로만 검증에 성공한다")
    void matchesOnlyOriginalPassword() {
        String encoded = passwordEncoder.encode(RAW_PASSWORD);

        assertThat(passwordEncoder.matches(RAW_PASSWORD, encoded)).isTrue();
        assertThat(passwordEncoder.matches("WrongPassword1", encoded)).isFalse();
    }

    @Test
    @DisplayName("해시가 password_hash 컬럼 상한을 넘지 않는다")
    void hashFitsColumnLength() {
        // password_hash는 VARCHAR(100). 넘치면 저장 시점에야 실패함.
        assertThat(passwordEncoder.encode(RAW_PASSWORD).length()).isLessThanOrEqualTo(100);
    }
}
