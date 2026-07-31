package com.okcare.assignment.health;

import com.okcare.assignment.IntegrationSupport;
import com.okcare.assignment.RegressionBaseline;
import com.okcare.assignment.health.infrastructure.HealthActivityRecordRepository;
import com.okcare.assignment.health.infrastructure.HealthConnectionRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * 건강 데이터 통합 테스트의 공통 부분. 저장과 집계 조회 테스트가 같은 fixture와 저장 경로를 씀.
 *
 * <p>함정: 상속하는 테스트는 가입 이메일에 클래스마다 다른 접두사를 붙일 것. 통합 테스트가
 * 컨테이너를 공유하므로 다른 클래스와 같은 이메일을 쓰면 가입이 409가 되어 엉뚱한 곳에서 실패함.
 */
public abstract class HealthIntegrationSupport extends IntegrationSupport {

    private static final Path FIXTURES = Path.of("fixtures/health");

    @Autowired protected HealthConnectionRepository connectionRepository;

    @Autowired protected HealthActivityRecordRepository recordRepository;

    /**
     * 앞선 테스트가 남긴 행 제거.
     *
     * <p>컨테이너를 클래스 사이에서 공유하는데 fixture의 {@code recordkey}가 고정값이라, 지우지
     * 않으면 앞 테스트가 소유권을 선점해 뒤 테스트의 최초 저장이 409가 됨. 전체 건수 단언도 앞
     * 테스트의 행이 섞여 무의미해짐.
     *
     * <p>레코드를 먼저 지움. 연결을 먼저 지우면 외래 키가 막음.
     */
    @BeforeEach
    void clearStoredHealthData() {
        recordRepository.deleteAllInBatch();
        connectionRepository.deleteAllInBatch();

        // 집계 캐시와 version 키도 비움. 행만 지우면 앞 테스트가 남긴 캐시가 적중해 DB를 비운 것이
        // 결과에 드러나지 않음. 운영 코드는 패턴 조회를 쓰지 않지만 테스트 정리에는 필요.
        redisTemplate.delete(redisTemplate.keys("health:*"));
    }

    protected ResultActions saveFixture(String accessToken, Path file) throws Exception {
        return save(accessToken, Files.readString(file));
    }

    protected ResultActions save(String accessToken, String body) throws Exception {
        return mockMvc.perform(
                MockMvcRequestBuilders.post("/api/v1/health-data")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    /**
     * 회귀 기준 월간 표의 첫 행 {@code recordkey}.
     *
     * <p>{@code RegressionBaseline}이 표의 순서를 보존하므로 실행마다 같은 값. 보존하지 않으면 이
     * 헬퍼가 실행마다 다른 recordkey를 돌려주고, fixture마다 행 수가 1,066~1,497로 달라 실행 계획
     * 단언이 간헐적으로 흔들림.
     */
    protected static String firstRecordKey() {
        return RegressionBaseline.load().monthlyTotals().keySet().iterator().next().recordKey();
    }

    /** 파일 이름으로 정렬. 회귀 단언이 읽는 순서에 의존하므로 디렉터리 나열 순서에 맡기지 않음. */
    protected static List<Path> fixtureFiles() {
        try (Stream<Path> files = Files.list(FIXTURES)) {
            return new ArrayList<>(
                    files.filter(path -> path.getFileName().toString().endsWith(".json"))
                            .sorted(Comparator.comparing(Path::toString))
                            .toList());
        } catch (IOException e) {
            throw new UncheckedIOException("fixture를 읽을 수 없습니다.", e);
        }
    }
}
