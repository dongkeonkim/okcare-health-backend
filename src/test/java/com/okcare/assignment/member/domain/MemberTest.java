package com.okcare.assignment.member.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 정규화가 어긋나면 UNIQUE 제약은 저장된 값만 비교하므로 대소문자만 다른 중복 계정이 생김. */
class MemberTest {

    @ParameterizedTest(name = "\"{0}\" → \"{1}\"")
    @DisplayName("이메일은 앞뒤 공백을 제거하고 소문자로 정규화한다")
    @CsvSource({
        "'gildong@example.com', 'gildong@example.com'",
        "'Gildong@Example.COM', 'gildong@example.com'",
        "'  gildong@example.com  ', 'gildong@example.com'",
        "'  GILDONG@EXAMPLE.COM  ', 'gildong@example.com'"
    })
    void normalizesEmail(String input, String expected) {
        assertThat(Member.normalizeEmail(input)).isEqualTo(expected);
    }

    @Test
    @DisplayName("정규화는 여러 번 적용해도 결과가 같다")
    void normalizationIsIdempotent() {
        // 요청 DTO와 저장 직전 두 곳에서 다듬으므로 멱등하지 않으면 값이 어긋남.
        String once = Member.normalizeEmail("  Gildong@Example.COM  ");

        assertThat(Member.normalizeEmail(once)).isEqualTo(once);
    }

    @Test
    @DisplayName("회원 생성 시 정규화된 이메일을 보관한다")
    void storesNormalizedEmail() {
        Member member =
                Member.create("홍길동", "길동", "  Gildong@Example.COM  ", "{bcrypt}$2a$10$hash");

        assertThat(member.getEmail()).isEqualTo("gildong@example.com");
    }
}
