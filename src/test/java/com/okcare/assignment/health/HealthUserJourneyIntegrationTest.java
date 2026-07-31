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
 * 한 사용자가 가입부터 로그아웃까지 연속으로 겪는 경로를 한 세션으로 검증.
 *
 * <p>다른 통합 테스트는 기능별로 갈라져 있고 각 클래스가 자기 로그인을 따로 함. 그래서 한 토큰이
 * 여러 엔드포인트를 지나는지, 앞 단계의 상태가 뒤 단계로 이어지는지가 검증 대상에서 빠짐.
 *
 * <p>세부 수치를 다시 단언하지 않음. 저장 카운트와 회귀값 대조는 각 기능 테스트가 이미 담당하고,
 * 여기서 또 세면 명세가 바뀔 때 고칠 곳이 두 배가 됨. 이 클래스는 <em>순서가 아니면 잡히지 않는
 * 것</em>만 확인.
 *
 * <p>단계를 메서드로 쪼개지 않음. 쪼개면 각 단계가 자기 세션을 새로 만들어 이 클래스의 존재 이유가
 * 사라짐. 대신 단언마다 단계 이름을 붙여 실패 지점이 드러나게 함.
 */
class HealthUserJourneyIntegrationTest extends HealthIntegrationSupport {

    private static final LocalDate RANGE_FROM = LocalDate.of(2024, 11, 1);
    private static final LocalDate RANGE_TO = LocalDate.of(2024, 12, 31);
    private static final YearMonth MONTH_FROM = YearMonth.of(2024, 11);
    private static final YearMonth MONTH_TO = YearMonth.of(2024, 12);

    @Test
    @DisplayName("가입부터 로그아웃까지 한 세션으로 저장·조회·재전송·재발급을 마친다")
    void walksWholeJourneyInOneSession() throws Exception {
        // 1·2. 가입과 로그인. 이후 모든 단계가 이 한 세션의 토큰을 씀.
        String email = "journey@example.com";
        signup(email);
        JsonNode tokens = loginSuccessfully(email);
        String accessToken = tokens.get("accessToken").asText();
        String refreshToken = tokens.get("refreshToken").asText();

        // 3. 네 JSON 저장. 한 토큰으로 서로 다른 recordkey 네 개를 연속 저장.
        for (Path file : fixtureFiles()) {
            saveFixture(accessToken, file)
                    .andExpect(status().isOk());
        }
        long storedRecords = recordRepository.count();
        assertThat(storedRecords).as("3단계 저장 행 수").isPositive();

        // 4. 저장 직후 같은 토큰으로 조회. 저장과 조회가 다른 엔드포인트라 토큰이 양쪽에 통해야 함.
        String recordKey = firstRecordKey();
        String dailyAfterSave = bodyOf(daily(accessToken, recordKey).andExpect(status().isOk()));
        String monthlyAfterSave =
                bodyOf(monthly(accessToken, recordKey).andExpect(status().isOk()));

        // 5. 재전송. 저장 행 수가 늘지 않아야 하고, 무효화 뒤 조회 값이 그대로여야 함.
        //
        // 네 파일을 다시 보내지 않고 조회 대상 recordkey의 파일만 보냄. 재전송 멱등성의 세부는 저장
        // 테스트가 담당하고, 여기서 확인할 것은 재전송이 조회 결과를 흔들지 않는다는 순서 성질뿐.
        // 네 파일을 다 보내면 fixture 저장이 여덟 번이 되어 전체 빌드가 두 배로 늘어남.
        saveFixture(accessToken, fixtureFiles().get(0)).andExpect(status().isOk());
        assertThat(recordRepository.count()).as("5단계 재전송 후 행 수").isEqualTo(storedRecords);
        assertThat(bodyOf(daily(accessToken, recordKey).andExpect(status().isOk())))
                .as("5단계 재전송 후 일간 응답")
                .isEqualTo(dailyAfterSave);
        assertThat(bodyOf(monthly(accessToken, recordKey).andExpect(status().isOk())))
                .as("5단계 재전송 후 월간 응답")
                .isEqualTo(monthlyAfterSave);

        // 6. 재발급. 새 액세스 토큰이 보호된 저장·조회 경로에도 통해야 함. 재발급 테스트는 재발급
        // 자체만 확인하므로 이 조합이 검증되지 않음.
        JsonNode reissued = readTree(bodyOf(refresh(refreshToken).andExpect(status().isOk())));
        String newAccessToken = reissued.get("accessToken").asText();
        String newRefreshToken = reissued.get("refreshToken").asText();
        saveFixture(newAccessToken, fixtureFiles().get(0))
                .andExpect(status().isOk());
        assertThat(bodyOf(daily(newAccessToken, recordKey).andExpect(status().isOk())))
                .as("6단계 재발급 토큰으로 조회한 일간 응답")
                .isEqualTo(dailyAfterSave);

        // 7. 로그아웃. 리프레시 토큰은 거절되지만 액세스 토큰은 자체 만료까지 유효한 것이 명세의
        // 계약. 그 계약이 보호된 다른 엔드포인트에서도 성립하는지는 확인된 적이 없음.
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
