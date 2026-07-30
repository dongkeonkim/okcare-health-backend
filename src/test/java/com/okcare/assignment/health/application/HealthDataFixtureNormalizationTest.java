package com.okcare.assignment.health.application;

import static java.math.BigDecimal.ZERO;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.okcare.assignment.RegressionBaseline;
import com.okcare.assignment.config.AppProperties;
import com.okcare.assignment.health.api.HealthDataRequest;
import com.okcare.assignment.health.domain.NormalizedPayload;
import com.okcare.assignment.health.domain.NormalizedRecord;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

/**
 * 과제가 제공한 네 입력 파일을 실제로 정규화해 기능 명세의 회귀 기준과 대조.
 *
 * <p>손으로 만든 입력만으로는 공급자 판별과 시각 형식이 실제 파일에서 성립하는지 알 수 없음.
 * 명세가 세는 0분 구간과 소수 걸음수가 정말 그만큼 있는지, 정규화가 그것을 버리지 않는지를
 * 여기서 고정.
 *
 * <p>기대값은 기능 명세의 회귀 기준에서 읽음. 테스트에 상수로 박으면 명세를 고치고 코드를
 * 고치지 않아도 통과함.
 *
 * <p>{@code new ObjectMapper()}가 아니라 {@link JsonTest}로 애플리케이션의 실제 설정을 가져옴.
 * 기본 설정은 미지의 필드에서 실패하지만 Spring Boot는 무시하도록 구성. 직접 만들면 테스트가
 * 앱보다 엄격해져, fixture의 {@code source.type}과 {@code data.memo}처럼 저장할 컬럼이 없어
 * DTO에 두지 않은 필드에서 앱은 통과하고 테스트만 실패함.
 */
@JsonTest
class HealthDataFixtureNormalizationTest {

    private static final Path FIXTURES = Path.of("fixtures/health");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired private ObjectMapper objectMapper;

    private RegressionBaseline baseline;
    private List<NormalizedPayload> payloads;

    @BeforeEach
    void normalizeAllFixtures() {
        baseline = RegressionBaseline.load();
        HealthDataNormalizer normalizer = new HealthDataNormalizer(new AppProperties(SEOUL));

        payloads = new ArrayList<>();
        try (Stream<Path> files = Files.list(FIXTURES)) {
            for (Path file :
                    files.filter(p -> p.getFileName().toString().endsWith(".json"))
                            .sorted(Comparator.comparing(Path::toString))
                            .toList()) {
                payloads.add(
                        normalizer.normalize(
                                objectMapper.readValue(file.toFile(), HealthDataRequest.class)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "fixture를 읽을 수 없습니다: " + FIXTURES.toAbsolutePath(), e);
        }

        assertThat(payloads).hasSize(4);
    }

    @Test
    @DisplayName("네 파일의 모든 엔트리가 예외 없이 정규화된다")
    void normalizesEveryEntry() {
        // 정규화 중 예외가 나면 준비 단계에서 먼저 실패함. 여기서는 한 건도 버려지지 않았는지 셈.
        int total = payloads.stream().mapToInt(NormalizedPayload::received).sum();

        assertThat(total).isEqualTo(baseline.count("총 수신 레코드"));
        assertThat(allRecords()).hasSize(baseline.count("총 수신 레코드"));
    }

    @Test
    @DisplayName("공급자별 엔트리 수가 회귀 기준과 일치한다")
    void countsEntriesPerProvider() {
        assertThat(countBySource("SamsungHealth")).isEqualTo(baseline.count("SamsungHealth"));
        assertThat(countBySource("Health Kit")).isEqualTo(baseline.count("Apple Health"));
    }

    @Test
    @DisplayName("삼성헬스의 0분 구간을 버리지 않는다")
    void keepsEveryZeroLengthPeriod() {
        // 공급자를 좁혀서 셈. 전체로 세면 삼성헬스 한 건이 사라지고 Apple Health 한 건이 생기는
        // 교환을 잡지 못함.
        long zeroLength =
                recordsOf("SamsungHealth").stream()
                        .filter(r -> r.periodStart().equals(r.periodEnd()))
                        .count();

        assertThat(zeroLength).isEqualTo(baseline.count("삼성헬스 0분 구간"));
    }

    @Test
    @DisplayName("원본 안에 같은 식별자가 중복되지 않는다")
    void hasNoDuplicateIdentityWithinPayload() {
        // 중복이 생기면 총 건수와 공급자별 건수는 그대로인데 저장 건수와 멱등 응답 기준이 달라짐.
        long duplicates = 0;
        for (NormalizedPayload payload : payloads) {
            long distinct =
                    payload.records().stream().map(NormalizedRecord::identity).distinct().count();
            duplicates += payload.received() - distinct;
        }

        assertThat(duplicates).isEqualTo(baseline.count("원본 내 동일 기간 중복"));
    }

    @Test
    @DisplayName("원본에 필수 필드가 빠진 엔트리가 없다")
    void hasNoEntryMissingRequiredField() {
        // 누락이 생기면 역직렬화나 정규화가 실패해 파일 전체가 400이 됨. 회귀 기준의 0건이
        // 그 전제를 명시한 값이라 대조 대상.
        long missing = 0;
        for (JsonNode entry : rawEntries()) {
            for (String field : List.of("period", "steps", "calories", "distance")) {
                if (!entry.hasNonNull(field)) {
                    missing++;
                    break;
                }
            }
        }

        assertThat(missing).isEqualTo(baseline.count("필수 필드 누락"));
    }

    @Test
    @DisplayName("문자열과 소수 걸음수가 회귀 기준만큼 들어 있다")
    void fixturesCarryExpectedStepsShapes() {
        // 정규화 후에는 BigDecimal 하나로 흡수되어 표기를 구분할 수 없으므로 원본 JSON에서 셈.
        // 이 단언이 지키는 것은 구현이 아니라 입력 파일이 명세가 말하는 그 파일이라는 사실.
        long asString = 0;
        long asDecimalString = 0;
        for (JsonNode steps : rawSteps()) {
            if (steps.isTextual()) {
                asString++;
                if (steps.asText().contains(".")) {
                    asDecimalString++;
                }
            }
        }

        assertThat(asString).isEqualTo(baseline.count("Apple Health 문자열 steps"));
        assertThat(asDecimalString).isEqualTo(baseline.count("Apple Health 소수 steps"));
    }

    @Test
    @DisplayName("소수 걸음수가 정수로 깎이지 않고 저장 정밀도까지 남는다")
    void keepsDecimalStepsToStoredScale() {
        // double을 거치거나 정수로 반올림하면 소수 걸음수가 사라짐. 저장 정밀도인 12자리까지는
        // 남아야 집계 회귀값이 유지됨.
        long withFraction =
                allRecords().stream()
                        .filter(r -> r.steps().stripTrailingZeros().scale() > 0)
                        .count();

        assertThat(withFraction).isEqualTo(baseline.count("Apple Health 소수 steps"));
        assertThat(maxStepsScale()).isEqualTo(12);
    }

    @Test
    @DisplayName("모든 측정값을 저장 정밀도인 소수점 12자리로 맞춘다")
    void quantizesEveryMeasurement() {
        // MySQL의 암묵적 반올림에 맡기면 저장값과 해시 대상이 어긋남. 원본에는 17~20자리 존재.
        assertThat(allRecords())
                .allSatisfy(
                        r -> {
                            assertThat(r.steps().scale()).isEqualTo(12);
                            assertThat(r.calories().scale()).isEqualTo(12);
                            assertThat(r.distance().scale()).isEqualTo(12);
                        });
    }

    @Test
    @DisplayName("모든 엔트리의 단위가 kcal과 km이다")
    void usesOnlySupportedUnits() {
        assertThat(allRecords())
                .allSatisfy(
                        r -> {
                            assertThat(r.caloriesUnit()).isEqualTo("kcal");
                            assertThat(r.distanceUnit()).isEqualTo("km");
                        });
    }

    @Test
    @DisplayName("오프셋 시각이 사업 기준 타임존으로 옮겨져 집계 날짜가 정해진다")
    void mapsOffsetTimeToBusinessZoneDate() {
        // Apple Health 첫 엔트리는 2024-11-14T21:20:00+0000이고 Asia/Seoul로는 11월 15일.
        // 오프셋을 무시하면 하루 앞 날짜에 쌓여 일간 회귀값이 전부 밀림.
        NormalizedPayload healthKit =
                payloads.stream()
                        .filter(p -> p.sourceName().equals("Health Kit"))
                        .findFirst()
                        .orElseThrow();

        assertThat(healthKit.records().get(0).activityDate())
                .isEqualTo(LocalDate.of(2024, 11, 15));
    }

    @Test
    @DisplayName("정규화한 값을 월별로 합산하면 회귀 기준의 월간 값과 일치한다")
    void reproducesMonthlyRegressionValues() {
        // 집계 쿼리는 경계 6에서 만들지만, 정규화 출력이 회귀 계약을 재현할 수 있는지는 지금
        // 고정해야 함. 경계 6에서 값이 어긋날 때 정규화와 쿼리 중 어디를 볼지 즉시 갈림.
        // 시각 해석이나 양자화를 잘못 건드리면 여기서 먼저 깨짐.
        Map<RegressionBaseline.MonthlyKey, BigDecimal[]> actual = new LinkedHashMap<>();
        for (NormalizedPayload payload : payloads) {
            for (NormalizedRecord record : payload.records()) {
                BigDecimal[] sum =
                        actual.computeIfAbsent(
                                monthKey(payload.recordKey(), record.activityDate()),
                                key -> new BigDecimal[] {ZERO, ZERO, ZERO});
                sum[0] = sum[0].add(record.steps());
                sum[1] = sum[1].add(record.calories());
                sum[2] = sum[2].add(record.distance());
            }
        }

        Map<RegressionBaseline.MonthlyKey, RegressionBaseline.MonthlyTotal> expected =
                baseline.monthlyTotals();

        // 키 집합을 대조. 건수만 비교하면 한 행을 지우고 다른 행을 복제해도 통과함.
        assertThat(actual.keySet()).containsExactlyInAnyOrderElementsOf(expected.keySet());

        expected.forEach(
                (key, total) -> {
                    BigDecimal[] sum = actual.get(key);

                    assertThat(sum[0].setScale(0, RoundingMode.HALF_UP).longValueExact())
                            .as("%s steps", key)
                            .isEqualTo(total.steps());
                    assertThat(sum[1].setScale(6, RoundingMode.HALF_UP))
                            .as("%s calories", key)
                            .isEqualByComparingTo(total.calories());
                    assertThat(sum[2].setScale(6, RoundingMode.HALF_UP))
                            .as("%s distance", key)
                            .isEqualByComparingTo(total.distance());
                });
    }

    @Test
    @DisplayName("연결 메타데이터를 원본 필드명 그대로 읽는다")
    void readsConnectionMetadata() {
        // vender는 공급자 원본 계약의 오탈자. 고치면 매핑이 끊어짐.
        assertThat(payloads)
                .allSatisfy(
                        p -> {
                            assertThat(p.recordKey()).hasSize(36);
                            assertThat(p.productName()).isNotBlank();
                            assertThat(p.vendorName()).isNotBlank();
                            assertThat(p.sourceMode()).isPositive();
                            assertThat(p.sourceLastUpdatedAt()).isNotNull();
                        });
        assertThat(payloads.stream().map(NormalizedPayload::recordKey).distinct()).hasSize(4);
    }

    private static RegressionBaseline.MonthlyKey monthKey(String recordKey, LocalDate date) {
        return new RegressionBaseline.MonthlyKey(recordKey, YearMonth.from(date));
    }

    private List<NormalizedRecord> allRecords() {
        return payloads.stream().flatMap(p -> p.records().stream()).toList();
    }

    private long countBySource(String sourceName) {
        return recordsOf(sourceName).size();
    }

    private List<NormalizedRecord> recordsOf(String sourceName) {
        return payloads.stream()
                .filter(p -> p.sourceName().equals(sourceName))
                .flatMap(p -> p.records().stream())
                .toList();
    }

    private int maxStepsScale() {
        return allRecords().stream()
                .map(NormalizedRecord::steps)
                .map(BigDecimal::stripTrailingZeros)
                .mapToInt(BigDecimal::scale)
                .max()
                .orElseThrow();
    }

    private List<JsonNode> rawSteps() {
        return rawEntries().stream().map(entry -> entry.get("steps")).toList();
    }

    private List<JsonNode> rawEntries() {
        List<JsonNode> entries = new ArrayList<>();
        try (Stream<Path> files = Files.list(FIXTURES)) {
            for (Path file :
                    files.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                for (JsonNode entry : objectMapper.readTree(file.toFile()).at("/data/entries")) {
                    entries.add(entry);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("fixture를 읽을 수 없습니다.", e);
        }
        return entries;
    }
}
