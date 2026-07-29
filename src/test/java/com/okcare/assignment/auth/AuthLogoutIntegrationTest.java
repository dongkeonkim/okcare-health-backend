package com.okcare.assignment.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;

class AuthLogoutIntegrationTest extends AuthIntegrationSupport {

    @Autowired private ApplicationContext applicationContext;

    @Test
    @DisplayName("actuator health는 인증 없이 UP을 반환한다")
    void exposesActuatorHealthWithoutAuthentication() throws Exception {
        // 이 경로가 막히면 Compose healthcheck의 grep이 실패해 app을 포함한 세 컨테이너가 모두
        // unhealthy가 되고 docker compose up이 끝나지 않음. 필터 체인이 생긴 뒤로는 설정 한 줄이
        // 실행 환경 전체를 깨뜨릴 수 있어 테스트로 고정.
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @ParameterizedTest(name = "{0} /logout")
    @DisplayName("프레임워크 기본 /logout 경로는 열려 있지 않다")
    @ValueSource(strings = {"GET", "POST", "PUT", "DELETE"})
    void doesNotExposeFrameworkLogoutPath(String method) throws Exception {
        // 기본 logout configurer를 끄지 않으면 LogoutFilter가 이 경로를 가로채 인증 없이
        // /login?logout으로 302를 보냄. 그 필터는 인가 필터보다 앞이라 authenticated() 규칙으로
        // 막히지 않음.
        mockMvc.perform(request(HttpMethod.valueOf(method), "/logout"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_ACCESS_TOKEN_INVALID"));
    }

    @Test
    @DisplayName("기본 사용자를 만들지 않아 생성 비밀번호를 로그에 남길 일이 없다")
    void definesNoDefaultUserDetailsService() {
        // UserDetailsService 계열 빈이 하나도 없으면 Spring Boot가 기본 사용자와 임의 비밀번호를
        // 만들고 그 평문을 시작 로그에 남김. 자동 구성 제외가 풀리면 이 단언이 먼저 깨짐.
        assertThat(applicationContext.getBeansOfType(UserDetailsService.class)).isEmpty();
    }

    @Test
    @DisplayName("공개 경로는 잘못된 Bearer 토큰이 붙어 있어도 본래 응답을 반환한다")
    void ignoresBrokenBearerTokenOnPublicPath() throws Exception {
        // 필터가 인증 실패를 직접 401로 쓰도록 바뀌면 가입·로그인·재발급과 health가 모두 막힘.
        signup("public@example.com");

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "email": "public@example.com",
                                          "password": "%s"
                                        }
                                        """
                                                .formatted(PASSWORD)))
                .andExpect(status().isOk());

        mockMvc.perform(
                        get("/actuator/health")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("로그아웃하면 204를 반환하고 그 리프레시 토큰으로 재발급할 수 없다")
    void revokesRefreshToken() throws Exception {
        signup("logout@example.com");
        JsonNode tokens = loginSuccessfully("logout@example.com");
        String refreshToken = tokens.get("refreshToken").asText();

        logout(tokens.get("accessToken").asText(), refreshToken).andExpect(status().isNoContent());

        refresh(refreshToken)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REFRESH_TOKEN_INVALID"));
    }

    @Test
    @DisplayName("이미 폐기된 리프레시 토큰으로 다시 로그아웃해도 204를 반환한다")
    void logoutIsIdempotent() throws Exception {
        // 재발급 뒤 로그아웃이나 재시도가 오류가 되면 클라이언트만 곤란해짐. 목표 상태인 "그
        // 토큰을 쓸 수 없음"은 이미 달성돼 있음.
        signup("idempotent@example.com");
        JsonNode tokens = loginSuccessfully("idempotent@example.com");
        String accessToken = tokens.get("accessToken").asText();
        String refreshToken = tokens.get("refreshToken").asText();

        logout(accessToken, refreshToken).andExpect(status().isNoContent());
        logout(accessToken, refreshToken).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("로그아웃해도 액세스 토큰은 자체 만료 시각까지 계속 인증에 쓸 수 있다")
    void keepsAccessTokenUsableAfterLogout() throws Exception {
        // 기능 명세가 명시적으로 요구하는 동작. 액세스 토큰 블랙리스트를 넣으면 여기서 깨짐.
        // 보호된 엔드포인트가 로그아웃뿐이라, 첫 로그아웃 뒤 같은 액세스 토큰으로 두 번째
        // 리프레시 토큰을 폐기해 액세스 토큰이 살아 있음을 확인.
        signup("survive@example.com");
        JsonNode first = loginSuccessfully("survive@example.com");
        JsonNode second = loginSuccessfully("survive@example.com");

        String accessToken = first.get("accessToken").asText();

        logout(accessToken, first.get("refreshToken").asText()).andExpect(status().isNoContent());
        logout(accessToken, second.get("refreshToken").asText()).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("다른 회원의 리프레시 토큰으로 로그아웃하면 401이고 그 토큰은 살아 있다")
    void rejectsOtherMembersRefreshToken() throws Exception {
        // 주체 일치 검사를 빼면 남의 토큰을 폐기할 수 있음. 액세스 토큰 주체로 키를 만드는
        // 구현으로 바꾸면 401 대신 204가 나가고 아무 토큰도 폐기되지 않음.
        signup("owner@example.com");
        signup("attacker@example.com");
        String victimRefreshToken =
                loginSuccessfully("owner@example.com").get("refreshToken").asText();
        String attackerAccessToken =
                loginSuccessfully("attacker@example.com").get("accessToken").asText();

        logout(attackerAccessToken, victimRefreshToken)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REFRESH_TOKEN_INVALID"));

        // 피해자의 토큰이 그대로 유효해야 폐기가 일어나지 않았다는 뜻.
        assertThat(refreshTokenOf(refresh(victimRefreshToken))).isNotBlank();
    }

    @Test
    @DisplayName("리프레시 토큰을 Authorization 헤더에 넣어 인증할 수 없다")
    void rejectsRefreshTokenAsAccessToken() throws Exception {
        // 14일 토큰으로 15분 액세스 토큰의 권한을 계속 행사하는 것을 막는 검사.
        signup("swap@example.com");
        String refreshToken = loginSuccessfully("swap@example.com").get("refreshToken").asText();

        logout(refreshToken, refreshToken)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_ACCESS_TOKEN_INVALID"));
    }
}
