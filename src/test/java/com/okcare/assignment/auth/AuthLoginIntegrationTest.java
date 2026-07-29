package com.okcare.assignment.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.okcare.assignment.TestSecrets;
import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 로그인은 MySQL 조회와 Redis 저장이 한 요청 안에서 모두 성공해야 하므로 두 인프라를 실제로 띄움.
 * 대역으로 바꾸면 TTL과 키 존재라는 검증 대상 자체가 사라짐.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(
        properties = {
            "JWT_SECRET=" + TestSecrets.JWT_SECRET,
            // application.yml의 placeholder 해석용. 실제 접속 정보는 @ServiceConnection이 덮어씀.
            // 접속할 수 없는 값을 넣어, 컨테이너 연결이 끊겼을 때 로컬에 떠 있는 Redis에 붙어
            // 조용히 통과하는 상황 방지.
            "REDIS_HOST=redis.invalid",
            "REDIS_PORT=1"
        })
class AuthLoginIntegrationTest {

    private static final String PASSWORD = "StrongPassword1";

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    // GenericContainer는 이미지에서 연결 종류를 유추할 수 없어 name을 명시해야 함.
    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Autowired private MockMvc mockMvc;

    @Autowired private StringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("로그인하면 리프레시 토큰의 해시가 jti를 키로 14일 TTL로 저장된다")
    void storesHashedRefreshTokenWithTtl() throws Exception {
        long memberId = signup("store@example.com");

        String refreshToken = loginSuccessfully("store@example.com").get("refreshToken").asText();
        String key = "auth:refresh:" + memberId + ":" + jti(refreshToken);

        // 평문이 저장되면 Redis 덤프만으로 남의 계정 토큰을 그대로 재발급에 쓸 수 있음. 기대값은
        // 운영 코드를 부르지 않고 따로 계산해, 다른 256비트 변환으로 바뀌면 드러나게 함.
        assertThat(redisTemplate.opsForValue().get(key)).isEqualTo(sha256Hex(refreshToken));

        // TTL이 없으면 폐기하지 않은 토큰이 영구히 남고, 14일을 넘으면 명세보다 오래 살아남음.
        assertThat(Duration.ofSeconds(redisTemplate.getExpire(key)))
                .isBetween(Duration.ofDays(13), Duration.ofDays(14));
    }

    @Test
    @DisplayName("대소문자와 앞뒤 공백이 다른 이메일로도 로그인할 수 있다")
    void acceptsNonNormalizedEmail() throws Exception {
        // 저장된 값이 정규화 형태이므로 조회 경로가 같은 규칙을 빠뜨리면 가입한 계정으로
        // 로그인할 수 없게 됨.
        signup("variant@example.com");

        login("  Variant@Example.COM  ", PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("등록되지 않은 이메일과 틀린 비밀번호의 401 응답이 서로 구별되지 않는다")
    void hidesWhetherEmailExists() throws Exception {
        // 두 응답이 조금이라도 다르면 로그인 화면이 가입 이메일을 확인하는 수단이 됨.
        signup("known@example.com");

        String unknownEmail = failureBody(login("unknown@example.com", PASSWORD));
        String wrongPassword = failureBody(login("known@example.com", "WrongPassword9"));

        assertThat(unknownEmail).isEqualTo(wrongPassword);
    }

    @Test
    @DisplayName("두 번 로그인하면 두 리프레시 토큰이 서로 다른 키로 공존한다")
    void keepsPreviousRefreshTokenOnSecondLogin() throws Exception {
        // 로그인이 기존 토큰을 폐기하지 않는다는 정책. 키가 회원 식별자만으로 구성되면 나중
        // 로그인이 앞선 기기의 토큰을 조용히 무효로 만듦.
        long memberId = signup("multi@example.com");

        String first = jti(loginSuccessfully("multi@example.com").get("refreshToken").asText());
        String second = jti(loginSuccessfully("multi@example.com").get("refreshToken").asText());

        assertThat(first).isNotEqualTo(second);
        assertThat(redisTemplate.opsForValue().get("auth:refresh:" + memberId + ":" + first))
                .isNotNull();
        assertThat(redisTemplate.opsForValue().get("auth:refresh:" + memberId + ":" + second))
                .isNotNull();
    }

    private long signup(String email) throws Exception {
        String body =
                """
                {
                  "name": "홍길동",
                  "nickname": "길동",
                  "email": "%s",
                  "password": "%s"
                }
                """
                        .formatted(email, PASSWORD);

        String response =
                mockMvc.perform(
                                post("/api/v1/auth/signup")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        return readTree(response).get("id").asLong();
    }

    private ResultActions login(String email, String password) throws Exception {
        String body =
                """
                {
                  "email": "%s",
                  "password": "%s"
                }
                """
                        .formatted(email, password);

        return mockMvc.perform(
                post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode loginSuccessfully(String email) throws Exception {
        return readTree(bodyOf(login(email, PASSWORD).andExpect(status().isOk())));
    }

    /** traceId와 timestamp는 요청마다 달라지므로 비교 대상에서 제외. */
    private String failureBody(ResultActions result) throws Exception {
        ObjectNode body =
                (ObjectNode) readTree(bodyOf(result.andExpect(status().isUnauthorized())));

        return body.remove(List.of("traceId", "timestamp")).toString();
    }

    private String jti(String token) {
        return Jwts.parser()
                .verifyWith(
                        new SecretKeySpec(
                                Base64.getDecoder().decode(TestSecrets.JWT_SECRET), "HmacSHA256"))
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getId();
    }

    private static String bodyOf(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    /** 저장 계약을 고정하기 위한 독립 계산. {@code RefreshTokenStore}의 메서드를 부르지 않음. */
    private static String sha256Hex(String value) throws Exception {
        return HexFormat.of()
                .formatHex(
                        MessageDigest.getInstance("SHA-256")
                                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new AssertionError("응답을 JSON으로 읽을 수 없습니다.", e);
        }
    }
}
