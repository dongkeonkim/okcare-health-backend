package com.okcare.assignment.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.okcare.assignment.TestSecrets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;

/**
 * Redis를 실제로 멈춘 상태에서도 집계 조회가 동작하는지 확인.
 *
 * <p>{@code IntegrationSupport}를 상속하지 않음. 그쪽 Redis 컨테이너는 모든 통합 테스트가
 * 공유하므로 여기서 멈추면 뒤따르는 테스트 클래스가 전부 죽음. 그래서 자체 컨테이너를 띄우고
 * 그것만 멈춤. 인증 헬퍼를 물려받지 못해 가입과 로그인을 직접 함.
 *
 * <p>대역으로 예외를 던지게 하지 않음. 그러면 우리 {@code catch}가 동작하는 것까지만 확인하고
 * 연결이 실제로 끊겼을 때를 검증하지 못함. 그쪽은 {@code HealthAggregationCacheTest}가 담당.
 */
@AutoConfigureMockMvc
@SpringBootTest(
        properties = {
            "JWT_SECRET=" + TestSecrets.JWT_SECRET,
            "REDIS_HOST=redis.invalid",
            "REDIS_PORT=1"
        })
class HealthCacheFallbackIntegrationTest {

    private static final Path FIXTURE = Path.of("fixtures/health/INPUT_DATA1.json");
    private static final String EMAIL = "cache-fallback@example.com";
    private static final String PASSWORD = "StrongPassword1";

    @ServiceConnection
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @ServiceConnection(name = "redis")
    static final GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        mysql.start();
        redis.start();
    }

    @Autowired private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Redis가 멈춰도 집계 조회가 200이고 값이 같다")
    void servesAggregationWhileRedisIsDown() throws Exception {
        // 로그인은 리프레시 토큰을 Redis에 저장하므로 멈추기 전에 끝내야 함. 액세스 토큰 검증은
        // 서명만 보므로 멈춘 뒤에도 유효.
        String accessToken = signupAndLogin();
        String body = Files.readString(FIXTURE);
        String recordKey = objectMapper.readTree(body).get("recordkey").asText();

        mockMvc.perform(
                        post("/api/v1/health-data")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isOk());

        String beforeDaily = bodyOf(daily(accessToken, recordKey));
        String beforeMonthly = bodyOf(monthly(accessToken, recordKey));

        redis.stop();

        // 명세는 캐시 장애 여부에 따라 응답 형식과 집계값이 달라지지 않을 것을 요구. 원문을
        // 비교해 형식과 값을 함께 확인.
        assertThat(bodyOf(daily(accessToken, recordKey))).isEqualTo(beforeDaily);
        assertThat(bodyOf(monthly(accessToken, recordKey))).isEqualTo(beforeMonthly);
    }

    private String signupAndLogin() throws Exception {
        String signup =
                """
                {
                  "name": "홍길동",
                  "nickname": "길동",
                  "email": "%s",
                  "password": "%s"
                }
                """
                        .formatted(EMAIL, PASSWORD);
        mockMvc.perform(
                        post("/api/v1/auth/signup")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(signup))
                .andExpect(status().isCreated());

        String login = """
                {
                  "email": "%s",
                  "password": "%s"
                }
                """.formatted(EMAIL, PASSWORD);
        String response =
                bodyOf(
                        mockMvc.perform(
                                post("/api/v1/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(login)));

        return objectMapper.readTree(response).get("accessToken").asText();
    }

    private static String bodyOf(ResultActions result) throws Exception {
        return result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private ResultActions daily(String accessToken, String recordKey) throws Exception {
        return mockMvc.perform(
                get("/api/v1/health-data/daily")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .param("recordKey", recordKey)
                        .param("from", "2024-11-01")
                        .param("to", "2024-12-31"));
    }

    private ResultActions monthly(String accessToken, String recordKey) throws Exception {
        return mockMvc.perform(
                get("/api/v1/health-data/monthly")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .param("recordKey", recordKey)
                        .param("from", "2024-11")
                        .param("to", "2024-12"));
    }
}
