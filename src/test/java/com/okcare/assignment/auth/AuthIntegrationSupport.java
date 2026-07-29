package com.okcare.assignment.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.okcare.assignment.TestSecrets;
import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
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

/**
 * 인증 흐름 통합 테스트의 공통 인프라와 헬퍼.
 *
 * <p>MySQL과 Redis를 실제로 띄움. 대역으로 바꾸면 TTL, 키 존재와 원자성이라는 검증 대상 자체가
 * 사라짐.
 *
 * <p>함정: 컨테이너를 {@code @Testcontainers}와 {@code @Container}로 관리하지 않고 정적 초기화
 * 블록에서 직접 시작함. 그 조합은 정적 컨테이너를 <em>클래스 단위</em>로 정지시키므로, 상속한 첫
 * 테스트 클래스가 끝나면 컨테이너가 내려가고 두 번째 클래스는 캐시된 Spring 컨텍스트로 죽은
 * 컨테이너에 접속해 모든 요청이 500이 됨. 직접 시작하면 JVM이 사는 동안 유지되고 정리는 Ryuk이
 * 담당.
 */
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
abstract class AuthIntegrationSupport {

    protected static final String PASSWORD = "StrongPassword1";

    @ServiceConnection
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    // GenericContainer는 이미지에서 연결 종류를 유추할 수 없어 name을 명시해야 함.
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        mysql.start();
        redis.start();
    }

    @Autowired protected MockMvc mockMvc;

    @Autowired protected StringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    protected long signup(String email) throws Exception {
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

        return readTree(
                        bodyOf(
                                mockMvc.perform(
                                                post("/api/v1/auth/signup")
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content(body))
                                        .andExpect(status().isCreated())))
                .get("id")
                .asLong();
    }

    protected ResultActions login(String email, String password) throws Exception {
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

    protected ResultActions refresh(String refreshToken) throws Exception {
        String body = """
                {
                  "refreshToken": "%s"
                }
                """.formatted(refreshToken);

        return mockMvc.perform(
                post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    protected JsonNode loginSuccessfully(String email) throws Exception {
        return readTree(bodyOf(login(email, PASSWORD).andExpect(status().isOk())));
    }

    protected String refreshTokenOf(ResultActions result) throws Exception {
        return readTree(bodyOf(result.andExpect(status().isOk()))).get("refreshToken").asText();
    }

    protected static String refreshTokenKey(long memberId, String tokenId) {
        return "auth:refresh:" + memberId + ":" + tokenId;
    }

    /** traceId와 timestamp는 요청마다 달라지므로 비교 대상에서 제외. */
    protected String failureBodyWithoutTrace(ResultActions result) throws Exception {
        ObjectNode body = (ObjectNode) readTree(bodyOf(result));

        return body.remove(List.of("traceId", "timestamp")).toString();
    }

    /** 서명 검증까지 통과해야 파싱되므로 설정한 secret으로 서명했는지도 함께 확인. */
    protected static String jti(String token) {
        return Jwts.parser()
                .verifyWith(testSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getId();
    }

    protected static SecretKeySpec testSigningKey() {
        return new SecretKeySpec(
                Base64.getDecoder().decode(TestSecrets.JWT_SECRET), "HmacSHA256");
    }

    /** 저장 계약을 고정하기 위한 독립 계산. {@code RefreshTokenStore}의 메서드를 부르지 않음. */
    protected static String sha256Hex(String value) throws Exception {
        return HexFormat.of()
                .formatHex(
                        MessageDigest.getInstance("SHA-256")
                                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    protected static String bodyOf(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    protected JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new AssertionError("응답을 JSON으로 읽을 수 없습니다.", e);
        }
    }
}
