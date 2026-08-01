package com.okcare.assignment.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.okcare.assignment.TestSecrets;
import com.okcare.assignment.common.error.BusinessException;
import com.okcare.assignment.common.error.ErrorCode;
import com.okcare.assignment.member.application.MemberSignupService;
import com.okcare.assignment.member.domain.Member;
import com.okcare.assignment.member.infrastructure.MemberRepository;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 인메모리 DB로 대체하면 제약 위반 예외 타입과 방언이 달라져 검증 대상 자체가 사라짐.
 *
 * <p>Redis는 회원가입 경로에서 쓰지 않으므로 컨테이너 없이 설정값만 채움. {@code JWT_SECRET}도
 * 마찬가지로 값만 채움. 서명 키는 경로와 무관하지만 안전 기준을 통과하지 못하면 컨텍스트 자체가
 * 뜨지 않음.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(
        properties = {
            "REDIS_HOST=localhost",
            "REDIS_PORT=6379",
            "JWT_SECRET=" + TestSecrets.JWT_SECRET
        })
class MemberSignupIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @Autowired private MockMvc mockMvc;

    @Autowired private MemberRepository memberRepository;

    @Autowired private MemberSignupService memberSignupService;

    @Test
    @DisplayName("가입하면 정규화된 이메일과 해시가 저장된다")
    void persistsNormalizedMember() throws Exception {
        signup("  Persist@Example.COM  ", "StrongPassword1").andExpect(status().isCreated());

        Member saved = findByEmail("persist@example.com");

        assertThat(saved.getName()).isEqualTo("홍길동");
        assertThat(saved.getPasswordHash()).startsWith("{bcrypt}$2a$");
        assertThat(saved.getPasswordHash()).doesNotContain("StrongPassword1");
    }

    @Test
    @DisplayName("같은 이메일로 다시 가입하면 409를 반환하고 회원이 늘지 않는다")
    void rejectsDuplicateEmail() throws Exception {
        signup("duplicate@example.com", "StrongPassword1").andExpect(status().isCreated());

        long countAfterFirst = memberRepository.count();

        signup("duplicate@example.com", "AnotherPassword2")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMBER_EMAIL_DUPLICATED"));

        assertThat(memberRepository.count()).isEqualTo(countAfterFirst);
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("대소문자와 공백만 다른 이메일도 중복으로 판정한다")
    @ValueSource(
            strings = {"Variant1@Example.com", "  variant2@example.com  ", "VARIANT3@EXAMPLE.COM"})
    void rejectsCaseAndWhitespaceVariants(String variant) throws Exception {
        // 케이스마다 다른 local part를 써서 파라미터 사이 간섭을 제거.
        // 정규화한 형태를 먼저 등록한 뒤 변형이 UNIQUE 제약에 걸리는지 확인.
        signup(Member.normalizeEmail(variant), "StrongPassword1").andExpect(status().isCreated());

        signup(variant, "StrongPassword1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMBER_EMAIL_DUPLICATED"));
    }

    @Test
    @DisplayName("같은 이메일로 동시에 가입해도 한 건만 저장된다")
    void allowsOnlyOneOfConcurrentSignups() throws Exception {
        // 순차 중복 테스트는 사전 조회 구현으로 되돌려도 통과한다. 동시 요청만이 UNIQUE 제약에
        // 판정을 맡긴 구조가 실제로 유효한지 보여준다.
        String email = "concurrent@example.com";
        long before = memberRepository.count();

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // submit으로 먼저 띄운 뒤 래치를 내린다. invokeAll은 모든 작업이 끝날 때까지
            // 블로킹하므로 래치를 그 뒤에 내리면 서로 기다리다 멈춘다.
            Future<Throwable> first = pool.submit(signupTask(start, email));
            Future<Throwable> second = pool.submit(signupTask(start, email));
            start.countDown();

            List<Throwable> outcomes =
                    java.util.Arrays.asList(
                            first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
            long succeeded = outcomes.stream().filter(java.util.Objects::isNull).count();
            long duplicated =
                    outcomes.stream()
                            .filter(BusinessException.class::isInstance)
                            .map(BusinessException.class::cast)
                            .filter(e -> e.errorCode() == ErrorCode.MEMBER_EMAIL_DUPLICATED)
                            .count();

            assertThat(succeeded).isEqualTo(1);
            assertThat(duplicated).isEqualTo(1);
            assertThat(memberRepository.count()).isEqualTo(before + 1);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private Callable<Throwable> signupTask(CountDownLatch start, String email) {
        return () -> {
            start.await();
            try {
                memberSignupService.signup("홍길동", "길동", email, "StrongPassword1");
                return null;
            } catch (Throwable t) {
                return t;
            }
        };
    }

    private org.springframework.test.web.servlet.ResultActions signup(String email, String password)
            throws Exception {
        String body =
                """
                {
                  "name": "홍길동",
                  "nickname": "길동",
                  "email": "%s",
                  "password": "%s"
                }
                """
                        .formatted(email, password);

        return mockMvc.perform(
                post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private Member findByEmail(String email) {
        return memberRepository.findAll().stream()
                .filter(member -> member.getEmail().equals(email))
                .findFirst()
                .orElseThrow(() -> new AssertionError("저장된 회원을 찾을 수 없습니다: " + email));
    }
}
