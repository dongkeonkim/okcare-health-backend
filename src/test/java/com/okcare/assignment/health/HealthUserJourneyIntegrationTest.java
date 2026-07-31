package com.okcare.assignment.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 가입부터 로그아웃까지 한 세션의 연속 사용자 흐름 검증.
 *
 * <p>로그아웃 후 토큰 상태가 보호 API까지 이어지는지 확인.
 * 세부 계약은 개별 테스트에 위임.
 */
class HealthUserJourneyIntegrationTest extends HealthIntegrationSupport {

    private static final LocalDate RANGE_FROM = LocalDate.of(2024, 11, 1);
    private static final LocalDate RANGE_TO = LocalDate.of(2024, 12, 31);
    private static final YearMonth MONTH_FROM = YearMonth.of(2024, 11);
    private static final YearMonth MONTH_TO = YearMonth.of(2024, 12);

    @Test
    @DisplayName("가입부터 로그아웃까지 한 세션으로 저장·조회·재전송·재발급을 마친다")
    void walksWholeJourneyInOneSession() throws Exception {
        String email = "journey@example.com";
        signup(email);
        JsonNode tokens = loginSuccessfully(email);
        String accessToken = tokens.get("accessToken").asText();
        String refreshToken = tokens.get("refreshToken").asText();

        for (Path file : fixtureFiles()) {
            saveFixture(accessToken, file)
                    .andExpect(status().isOk());
        }
        long storedRecords = recordRepository.count();
        assertThat(storedRecords).as("3단계 저장 행 수").isPositive();

        String recordKey = firstRecordKey();
        String dailyAfterSave = bodyOf(daily(accessToken, recordKey).andExpect(status().isOk()));
        String monthlyAfterSave =
                bodyOf(monthly(accessToken, recordKey).andExpect(status().isOk()));

        // 단일 recordkey 재전송으로 상태 유지와 캐시 무효화 순서 확인.
        saveFixture(accessToken, fixtureFiles().get(0)).andExpect(status().isOk());
        assertThat(recordRepository.count()).as("5단계 재전송 후 행 수").isEqualTo(storedRecords);
        assertThat(bodyOf(daily(accessToken, recordKey).andExpect(status().isOk())))
                .as("5단계 재전송 후 일간 응답")
                .isEqualTo(dailyAfterSave);
        assertThat(bodyOf(monthly(accessToken, recordKey).andExpect(status().isOk())))
                .as("5단계 재전송 후 월간 응답")
                .isEqualTo(monthlyAfterSave);

        // 재발급한 액세스 토큰으로 보호 API 접근 가능 여부 확인.
        JsonNode reissued = readTree(bodyOf(refresh(refreshToken).andExpect(status().isOk())));
        String newAccessToken = reissued.get("accessToken").asText();
        String newRefreshToken = reissued.get("refreshToken").asText();
        saveFixture(newAccessToken, fixtureFiles().get(0))
                .andExpect(status().isOk());
        assertThat(bodyOf(daily(newAccessToken, recordKey).andExpect(status().isOk())))
                .as("6단계 재발급 토큰으로 조회한 일간 응답")
                .isEqualTo(dailyAfterSave);

        // 로그아웃 후 리프레시는 거절. 액세스는 만료 전까지 허용.
        logout(newAccessToken, newRefreshToken).andExpect(status().isNoContent());
        refresh(newRefreshToken).andExpect(status().isUnauthorized());
        daily(newAccessToken, recordKey).andExpect(status().isOk());
        monthly(newAccessToken, recordKey).andExpect(status().isOk());
    }

    private ResultActions daily(String accessToken, String recordKey) throws Exception {
        return mockMvc.perform(
                        get("/api/v1/health-data/daily")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                                .param("recordKey", recordKey)
                                .param("from", RANGE_FROM.toString())
                                .param("to", RANGE_TO.toString()));
    }

    private ResultActions monthly(String accessToken, String recordKey) throws Exception {
        return mockMvc.perform(
                        get("/api/v1/health-data/monthly")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                                .param("recordKey", recordKey)
                                .param("from", MONTH_FROM.toString())
                                .param("to", MONTH_TO.toString()));
    }
}
