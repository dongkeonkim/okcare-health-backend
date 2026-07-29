package com.okcare.assignment.auth.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.okcare.assignment.auth.application.LoginService;
import com.okcare.assignment.auth.application.TokenRefreshService;
import com.okcare.assignment.auth.domain.IssuedTokens;
import com.okcare.assignment.common.error.BusinessException;
import com.okcare.assignment.common.error.ErrorCode;
import com.okcare.assignment.common.error.GlobalExceptionHandler;
import com.okcare.assignment.config.TimeConfig;
import com.okcare.assignment.member.application.MemberSignupService;
import com.okcare.assignment.member.domain.Member;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Service를 대역으로 두고 HTTP 계약만 확인. 정규화와 중복 판정은 단위·통합 테스트가 담당. */
@WebMvcTest(AuthController.class)
@Import({GlobalExceptionHandler.class, TimeConfig.class})
class AuthControllerTest {

    private static final String VALID_BODY =
            """
            {
              "name": "홍길동",
              "nickname": "길동",
              "email": "gildong@example.com",
              "password": "StrongPassword1"
            }
            """;

    private static final String TOKEN_ID = "11111111-1111-1111-1111-111111111111";

    private static final String LOGIN_BODY =
            """
            {
              "email": "gildong@example.com",
              "password": "StrongPassword1"
            }
            """;

    private static final String REFRESH_BODY = "{\"refreshToken\": \"refresh.token.value\"}";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private MemberSignupService memberSignupService;

    @MockitoBean private LoginService loginService;

    @MockitoBean private TokenRefreshService tokenRefreshService;

    @Test
    @DisplayName("가입에 성공하면 201과 회원 정보를 반환한다")
    void returnsCreated() throws Exception {
        givenSignupReturnsMember();

        signup(VALID_BODY)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("홍길동"))
                .andExpect(jsonPath("$.nickname").value("길동"))
                .andExpect(jsonPath("$.email").value("gildong@example.com"));
    }

    @Test
    @DisplayName("성공 응답에 비밀번호와 해시를 노출하지 않는다")
    void neverExposesPassword() throws Exception {
        givenSignupReturnsMember();

        String body =
                signup(VALID_BODY)
                        .andExpect(jsonPath("$.password").doesNotExist())
                        .andExpect(jsonPath("$.passwordHash").doesNotExist())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        // 필드 이름뿐 아니라 값 자체가 어디에도 실려 나가면 안 됨.
        assertThat(body).doesNotContain("StrongPassword1").doesNotContain("$2a$");
    }

    @Test
    @DisplayName("이메일 앞뒤 공백은 형식 위반으로 보지 않고 다듬어 전달한다")
    void trimsEmailBeforeValidation() throws Exception {
        givenSignupReturnsMember();

        String body = VALID_BODY.replace("\"gildong@example.com\"", "\"  Gildong@Example.COM  \"");

        signup(body).andExpect(status().isCreated());

        verify(memberSignupService).signup(any(), any(), eq("Gildong@Example.COM"), any());
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("검증에 실패하면 400과 필드 오류를 반환한다")
    @CsvSource({
        "이름 누락, name",
        "닉네임 누락, nickname",
        "이메일 누락, email",
        "비밀번호 누락, password"
    })
    void rejectsMissingField(String label, String field) throws Exception {
        String body =
                VALID_BODY.replaceAll(
                        "\"" + field + "\"\\s*:\\s*\"[^\"]*\"", "\"" + field + "\": \"\"");

        signup(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(fieldErrorOn(field))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());

        verify(memberSignupService, never()).signup(any(), any(), any(), any());
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("비밀번호 정책을 어기면 400을 반환한다")
    @CsvSource({
        "8자 미만, Short1",
        "64자 초과, aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1",
        "숫자 없음, PasswordOnly",
        "영문자 없음, 12345678"
    })
    void rejectsWeakPassword(String label, String password) throws Exception {
        String body = VALID_BODY.replace("\"StrongPassword1\"", "\"" + password + "\"");

        String responseBody =
                signup(body)
                        .andExpect(status().isBadRequest())
                        .andExpect(fieldErrorOn("password"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        // 검증 실패 응답에 거부된 비밀번호가 되비쳐 나오면 안 됨.
        assertThat(responseBody).doesNotContain(password);
        verify(memberSignupService, never()).signup(any(), any(), any(), any());
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("필드가 길이 상한을 넘으면 400을 반환한다")
    @CsvSource({"이름 51자, name, 51", "닉네임 51자, nickname, 51"})
    void rejectsTooLongField(String label, String field, int length) throws Exception {
        String body = replaceField(VALID_BODY, field, "가".repeat(length));

        signup(body).andExpect(status().isBadRequest()).andExpect(fieldErrorOn(field));

        verify(memberSignupService, never()).signup(any(), any(), any(), any());
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("필드가 길이 상한과 같으면 통과한다")
    @CsvSource({"이름 50자, name, 50", "닉네임 50자, nickname, 50"})
    void acceptsFieldAtMaxLength(String label, String field, int length) throws Exception {
        // 상한 자체를 막아버리는 off-by-one을 잡는다.
        givenSignupReturnsMember();
        String body = replaceField(VALID_BODY, field, "가".repeat(length));

        signup(body).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("이메일이 255자를 넘으면 400을 반환한다")
    void rejectsTooLongEmail() throws Exception {
        String local = "a".repeat(256 - "@example.com".length());
        String body = replaceField(VALID_BODY, "email", local + "@example.com");

        signup(body).andExpect(status().isBadRequest()).andExpect(fieldErrorOn("email"));

        verify(memberSignupService, never()).signup(any(), any(), any(), any());
    }

    @Test
    @DisplayName("지원하지 않는 Content-Type은 500이 아니라 4xx를 반환한다")
    void rejectsUnsupportedContentType() throws Exception {
        // 전역 Exception 처리기가 MVC 요청 오류까지 삼키면 여기서 500이 된다.
        mockMvc.perform(
                        post("/api/v1/auth/signup")
                                .contentType(MediaType.TEXT_PLAIN)
                                .content(VALID_BODY))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("허용하지 않는 메서드는 500이 아니라 405를 반환한다")
    void rejectsUnsupportedMethod() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/v1/auth/signup"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("이메일 형식이 아니면 400을 반환한다")
    void rejectsMalformedEmail() throws Exception {
        String body = VALID_BODY.replace("\"gildong@example.com\"", "\"not-an-email\"");

        signup(body).andExpect(status().isBadRequest()).andExpect(fieldErrorOn("email"));
    }

    @Test
    @DisplayName("JSON으로 해석할 수 없는 본문은 400을 반환한다")
    void rejectsUnreadableBody() throws Exception {
        signup("{")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("이메일이 중복되면 409를 반환한다")
    void returnsConflictOnDuplicateEmail() throws Exception {
        given(memberSignupService.signup(any(), any(), any(), any()))
                .willThrow(new BusinessException(ErrorCode.MEMBER_EMAIL_DUPLICATED));

        signup(VALID_BODY)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMBER_EMAIL_DUPLICATED"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("로그인에 성공하면 200과 토큰 쌍을 반환한다")
    void returnsTokensOnLogin() throws Exception {
        givenLoginReturnsTokens();

        login(LOGIN_BODY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.accessToken").value("access.token.value"))
                .andExpect(jsonPath("$.accessTokenExpiresIn").value(900))
                .andExpect(jsonPath("$.refreshToken").value("refresh.token.value"))
                .andExpect(jsonPath("$.refreshTokenExpiresIn").value(1_209_600));
    }

    @Test
    @DisplayName("로그인 응답에 비밀번호와 저장 키 식별자를 담지 않는다")
    void loginResponseCarriesOnlyContractFields() throws Exception {
        givenLoginReturnsTokens();

        String body = login(LOGIN_BODY).andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("StrongPassword1");
        // 응답 계약은 다섯 필드뿐. tokenId가 새 필드로 새어 나가면 계약이 조용히 넓어짐.
        assertThat(body).doesNotContain(TOKEN_ID);
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("로그인 필수 필드가 없으면 400을 반환하고 인증을 시도하지 않는다")
    @CsvSource({"이메일 누락, email", "비밀번호 누락, password"})
    void rejectsMissingLoginField(String label, String field) throws Exception {
        String body = replaceField(LOGIN_BODY, field, "");

        login(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(fieldErrorOn(field));

        verify(loginService, never()).login(any(), any());
    }

    @Test
    @DisplayName("자격 증명이 틀리면 401을 반환하고 필드 오류를 붙이지 않는다")
    void returnsUnauthorizedOnInvalidCredentials() throws Exception {
        givenLoginRejectsCredentials();

        login(LOGIN_BODY)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_CREDENTIALS_INVALID"))
                // 필드 오류가 붙으면 이메일과 비밀번호 중 무엇이 틀렸는지 알려 주게 됨.
                .andExpect(jsonPath("$.fieldErrors").isEmpty());
    }

    @Test
    @DisplayName("이메일 형식이 아니어도 400이 아니라 인증 결과로 판정한다")
    void doesNotValidateLoginEmailFormat() throws Exception {
        // 로그인에 형식 제약을 붙이면 가입 규칙을 강화한 뒤 그 전에 만든 계정이 400으로 막히고,
        // 400과 401의 차이가 이메일 형식이 유효한지 알려 주는 신호가 됨.
        givenLoginRejectsCredentials();

        String body = replaceField(LOGIN_BODY, "email", "not-an-email");

        login(body).andExpect(status().isUnauthorized());

        verify(loginService).login(eq("not-an-email"), any());
    }

    @Test
    @DisplayName("재발급에 성공하면 200과 로그인과 같은 형식의 토큰 쌍을 반환한다")
    void returnsTokensOnRefresh() throws Exception {
        // 두 엔드포인트가 응답 계약을 공유하므로 한쪽만 필드가 어긋나는 회귀 검출용.
        given(tokenRefreshService.refresh(any())).willReturn(issuedTokens());

        refresh(REFRESH_BODY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.accessToken").value("access.token.value"))
                .andExpect(jsonPath("$.accessTokenExpiresIn").value(900))
                .andExpect(jsonPath("$.refreshToken").value("refresh.token.value"))
                .andExpect(jsonPath("$.refreshTokenExpiresIn").value(1_209_600));
    }

    @Test
    @DisplayName("리프레시 토큰이 없으면 400을 반환하고 교체를 시도하지 않는다")
    void rejectsMissingRefreshToken() throws Exception {
        refresh("{\"refreshToken\": \"\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(fieldErrorOn("refreshToken"));

        verify(tokenRefreshService, never()).refresh(any());
    }

    @Test
    @DisplayName("토큰이 유효하지 않으면 401을 반환하고 필드 오류를 붙이지 않는다")
    void returnsUnauthorizedOnInvalidRefreshToken() throws Exception {
        given(tokenRefreshService.refresh(any()))
                .willThrow(new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_INVALID));

        refresh(REFRESH_BODY)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REFRESH_TOKEN_INVALID"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty());
    }

    @Test
    @DisplayName("교체를 완료하지 못하면 500을 반환한다")
    void returnsServerErrorWhenRotateFails() throws Exception {
        given(tokenRefreshService.refresh(any()))
                .willThrow(new BusinessException(ErrorCode.AUTH_TOKEN_ROTATE_FAILED));

        refresh(REFRESH_BODY)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("AUTH_TOKEN_ROTATE_FAILED"));
    }

    private static String replaceField(String body, String field, String value) {
        return body.replaceAll(
                "\"" + field + "\"\\s*:\\s*\"[^\"]*\"", "\"" + field + "\": \"" + value + "\"");
    }

    private ResultActions signup(String body) throws Exception {
        return mockMvc.perform(
                post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions login(String body) throws Exception {
        return mockMvc.perform(
                post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions refresh(String body) throws Exception {
        return mockMvc.perform(
                post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private void givenLoginReturnsTokens() {
        given(loginService.login(any(), any())).willReturn(issuedTokens());
    }

    private static IssuedTokens issuedTokens() {
        return new IssuedTokens(
                TOKEN_ID, "access.token.value", 900, "refresh.token.value", 1_209_600);
    }

    private void givenLoginRejectsCredentials() {
        given(loginService.login(any(), any()))
                .willThrow(new BusinessException(ErrorCode.AUTH_CREDENTIALS_INVALID));
    }

    private void givenSignupReturnsMember() {
        given(memberSignupService.signup(any(), any(), any(), any()))
                .willReturn(savedMember(1L, "gildong@example.com"));
    }

    private static org.springframework.test.web.servlet.ResultMatcher fieldErrorOn(String field) {
        return jsonPath("$.fieldErrors[?(@.field == '" + field + "')]").exists();
    }

    private static Member savedMember(long id, String email) {
        Member member = Member.create("홍길동", "길동", email, "{bcrypt}$2a$10$hash");
        // id는 DB가 채우므로 대역 응답에서는 직접 주입.
        ReflectionTestUtils.setField(member, "id", id);
        return member;
    }
}
