package com.okcare.assignment.health.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.okcare.assignment.auth.infrastructure.JwtTokenProvider;
import com.okcare.assignment.common.error.BusinessException;
import com.okcare.assignment.common.error.ErrorCode;
import com.okcare.assignment.common.error.GlobalExceptionHandler;
import com.okcare.assignment.common.security.JwtAuthenticationFilter;
import com.okcare.assignment.common.security.TokenAuthenticationEntryPoint;
import com.okcare.assignment.config.AppProperties;
import com.okcare.assignment.config.SecurityConfig;
import com.okcare.assignment.config.TimeConfig;
import com.okcare.assignment.health.application.HealthAggregationService;
import com.okcare.assignment.health.application.HealthDataService;
import com.okcare.assignment.health.domain.DailyTotal;
import com.okcare.assignment.health.domain.MonthlyTotal;
import com.okcare.assignment.health.domain.SaveResult;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 조회 API의 HTTP 계약만 확인. 집계값과 반올림은 단위·통합 테스트가 담당.
 *
 * <p>필터를 끄지 않고 실제 {@link SecurityConfig}를 가져옴. 끄면 조회 경로가 보호되는지가 검증
 * 대상에서 빠져, 공개 경로로 잘못 넣어도 통과함.
 */
@WebMvcTest(HealthDataController.class)
@Import({
    GlobalExceptionHandler.class,
    TimeConfig.class,
    SecurityConfig.class,
    JwtAuthenticationFilter.class,
    TokenAuthenticationEntryPoint.class
})
class HealthDataControllerTest {

    private static final String RECORD_KEY = "7836887b-b12a-440f-af0f-851546504b13";
    private static final String ACCESS_TOKEN = "access.token.value";
    private static final long MEMBER_ID = 7L;

    @Autowired private MockMvc mockMvc;

    @MockitoBean private HealthDataService healthDataService;

    @MockitoBean private HealthAggregationService healthAggregationService;

    @MockitoBean private JwtTokenProvider jwtTokenProvider;

    /**
     * 슬라이스에는 actuator 자동 구성과 설정 속성 바인딩이 없어 SecurityConfig와 컨트롤러가 읽는
     * 값만 직접 채움.
     */
    @TestConfiguration
    static class SliceConfig {

        @Bean
        WebEndpointProperties webEndpointProperties() {
            return new WebEndpointProperties();
        }

        @Bean
        AppProperties appProperties() {
            return new AppProperties(ZoneId.of("Asia/Seoul"));
        }
    }

    @Test
    @DisplayName("토큰이 없으면 401과 공통 오류 형식을 반환한다")
    void rejectsUnauthenticated() throws Exception {
        mockMvc.perform(dailyRequest())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.AUTH_ACCESS_TOKEN_INVALID.name()))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    @DisplayName("조회에 성공하면 200과 응답 계약의 필드를 반환한다")
    void returnsDailyTotals() throws Exception {
        givenAuthenticated();
        given(healthAggregationService.daily(anyLong(), anyString(), any(), any()))
                .willReturn(List.of(DailyTotal.empty(LocalDate.of(2024, 11, 1))));

        daily("2024-11-01", "2024-11-30")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordkey").value(RECORD_KEY))
                .andExpect(jsonPath("$.recordKey").doesNotExist())
                .andExpect(jsonPath("$.zoneId").value("Asia/Seoul"))
                .andExpect(jsonPath("$.items[0].date").value("2024-11-01"))
                .andExpect(jsonPath("$.items[0].steps").value(0));
    }

    @Test
    @DisplayName("가변 길이 recordkey를 저장하고 같은 필드명으로 응답한다")
    void acceptsVariableLengthRecordkey() throws Exception {
        givenAuthenticated();
        given(healthDataService.save(anyLong(), any())).willReturn(new SaveResult(1, 1, 0, 0));
        String recordkey = "supplier-user-key";

        mockMvc.perform(
                        withToken(
                                post("/api/v1/health-data")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(saveBody(recordkey))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordkey").value(recordkey))
                .andExpect(jsonPath("$.recordKey").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("비어 있는 recordkey는 400을 반환한다")
    void rejectsBlankRecordkey(String recordkey) throws Exception {
        givenAuthenticated();

        mockMvc.perform(
                        withToken(
                                post("/api/v1/health-data")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(saveBody(recordkey))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("recordkey"));
    }

    @Test
    @DisplayName("255자를 넘는 recordkey는 400을 반환한다")
    void rejectsRecordkeyOverColumnLimit() throws Exception {
        givenAuthenticated();

        mockMvc.perform(
                        withToken(
                                post("/api/v1/health-data")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(saveBody("r".repeat(256)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("recordkey"));
    }

    @ParameterizedTest
    @CsvSource({
        "/api/v1/health-data/daily, 2024-11-01, 2024-11-30",
        "/api/v1/health-data/monthly, 2024-11, 2024-12"
    })
    @DisplayName("조회 recordkey가 255자를 넘으면 400을 반환한다")
    void rejectsRecordkeyOverColumnLimitOnQueries(String path, String from, String to)
            throws Exception {

        givenAuthenticated();

        mockMvc.perform(
                        withToken(
                                get(path)
                                        .param("recordkey", "r".repeat(256))
                                        .param("from", from)
                                        .param("to", to)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_REQUEST.name()));
    }

    @Test
    @DisplayName("entries에 null 항목이 있으면 400을 반환한다")
    void rejectsNullEntry() throws Exception {
        givenAuthenticated();

        mockMvc.perform(
                        withToken(
                                post("/api/v1/health-data")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(saveBodyWithNullEntry())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("data.entries[0]"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2024-13-01", "2024-02-30", "2024/11/01", "20241101", "어제"})
    @DisplayName("날짜 형식이 어긋나면 400과 공통 오류 형식을 반환한다")
    void rejectsMalformedDate(String from) throws Exception {
        givenAuthenticated();

        // 상속한 기본 처리기는 이 예외를 RFC 7807 형식으로 내보냄. 공통 오류 형식으로 나오는지가
        // 검증 대상이고, 어긋나면 클라이언트가 오류 응답을 한 가지로 다룰 수 없음.
        daily(from, "2024-11-30")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_REQUEST.name()))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"recordkey", "from", "to"})
    @DisplayName("필수 파라미터가 빠지면 400과 공통 오류 형식을 반환한다")
    void rejectsMissingParameter(String omitted) throws Exception {
        givenAuthenticated();

        mockMvc.perform(withToken(dailyRequestWithout(omitted)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_REQUEST.name()));
    }

    @Test
    @DisplayName("조회할 수 없는 recordkey는 404를 반환한다")
    void mapsNotFoundToStatus() throws Exception {
        givenAuthenticated();
        willThrow(new BusinessException(ErrorCode.HEALTH_RECORD_KEY_NOT_FOUND))
                .given(healthAggregationService)
                .daily(anyLong(), anyString(), any(), any());

        daily("2024-11-01", "2024-11-30")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.HEALTH_RECORD_KEY_NOT_FOUND.name()));
    }

    @Test
    @DisplayName("월간 조회에 성공하면 200과 yyyy-MM 형식의 month를 반환한다")
    void returnsMonthlyTotals() throws Exception {
        givenAuthenticated();
        given(healthAggregationService.monthly(anyLong(), anyString(), any(), any()))
                .willReturn(List.of(MonthlyTotal.empty(YearMonth.of(2024, 11))));

        monthly("2024-11", "2024-12")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordkey").value(RECORD_KEY))
                .andExpect(jsonPath("$.recordKey").doesNotExist())
                .andExpect(jsonPath("$.zoneId").value("Asia/Seoul"))
                .andExpect(jsonPath("$.items[0].month").value("2024-11"))
                .andExpect(jsonPath("$.items[0].steps").value(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2024-13", "2024/11", "202411", "2024-11-01", "지난달"})
    @DisplayName("월 형식이 어긋나면 400과 공통 오류 형식을 반환한다")
    void rejectsMalformedMonth(String from) throws Exception {
        givenAuthenticated();

        // yyyy-MM-dd를 넣어도 거절해야 함. 일간 파라미터를 그대로 붙여 보내는 실수를 통과시키면
        // 어느 달로 집계됐는지 알 수 없게 됨.
        monthly(from, "2024-12")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_REQUEST.name()));
    }

    @Test
    @DisplayName("월간 조회도 토큰이 없으면 401을 반환한다")
    void rejectsUnauthenticatedMonthly() throws Exception {
        mockMvc.perform(get("/api/v1/health-data/monthly"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.AUTH_ACCESS_TOKEN_INVALID.name()));
    }

    private ResultActions monthly(String from, String to) throws Exception {
        return mockMvc.perform(
                withToken(
                        get("/api/v1/health-data/monthly")
                                .param("recordkey", RECORD_KEY)
                                .param("from", from)
                                .param("to", to)));
    }

    private void givenAuthenticated() {
        given(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN)).willReturn(MEMBER_ID);
    }

    private ResultActions daily(String from, String to) throws Exception {
        return mockMvc.perform(
                withToken(
                        dailyRequest()
                                .param("recordkey", RECORD_KEY)
                                .param("from", from)
                                .param("to", to)));
    }

    /**
     * 파라미터를 붙이지 않은 요청. 인증 실패는 파라미터 검증보다 먼저 일어나므로 인증 테스트가
     * 그대로 씀.
     */
    private static MockHttpServletRequestBuilder dailyRequest() {
        return get("/api/v1/health-data/daily");
    }

    private static MockHttpServletRequestBuilder dailyRequestWithout(String omitted) {
        MockHttpServletRequestBuilder request = dailyRequest();
        if (!"recordkey".equals(omitted)) {
            request = request.param("recordkey", RECORD_KEY);
        }
        if (!"from".equals(omitted)) {
            request = request.param("from", "2024-11-01");
        }
        if (!"to".equals(omitted)) {
            request = request.param("to", "2024-11-30");
        }

        return request;
    }

    private static String saveBody(String recordkey) {
        return """
                {
                  "recordkey": "%s",
                  "type": "steps",
                  "lastUpdate": "2024-11-15 10:00:00 +0900",
                  "data": {
                    "source": {
                      "name": "SamsungHealth",
                      "mode": 9,
                      "product": {
                        "name": "Android",
                        "vender": "Samsung"
                      }
                    },
                    "entries": [
                      {
                        "period": {
                          "from": "2024-11-15 09:00:00",
                          "to": "2024-11-15 09:10:00"
                        },
                        "steps": 10,
                        "calories": {
                          "value": 1,
                          "unit": "kcal"
                        },
                        "distance": {
                          "value": 0.01,
                          "unit": "km"
                        }
                      }
                    ]
                  }
                }
                """
                .formatted(recordkey);
    }

    private static String saveBodyWithNullEntry() {
        return """
                {
                  "recordkey": "%s",
                  "type": "steps",
                  "lastUpdate": "2024-11-15 10:00:00 +0900",
                  "data": {
                    "source": {
                      "name": "SamsungHealth",
                      "mode": 9,
                      "product": {
                        "name": "Android",
                        "vender": "Samsung"
                      }
                    },
                    "entries": [null]
                  }
                }
                """
                .formatted(RECORD_KEY);
    }

    private static MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN);
    }
}
