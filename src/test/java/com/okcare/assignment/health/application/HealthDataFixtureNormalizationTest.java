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
 * 제공 fixture를 실제 {@link JsonTest} 설정으로 정규화하고 회귀 기준과 대조.
 *
 * <p>공급자 형식, 0분 구간과 소수 걸음수를 검증.
 * 저장하지 않는 원본 필드가 포함된 fixture도 JsonTest 설정으로 읽음.
 * 기대값은 {@link RegressionBaseline}에서 읽어 테스트와 기준의 중복 방지.
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
        // 공급자별로 세어 레코드 교환을 검출.
        long zeroLength =
                recordsOf("SamsungHealth").stream()
                        .filter(r -> r.periodStart().equals(r.periodEnd()))
                        .count();

        assertThat(zeroLength).isEqualTo(baseline.count("삼성헬스 0분 구간"));
    }

    @Test
    @DisplayName("원본 안에 같은 식별자가 중복되지 않는다")
    void hasNoDuplicateIdentityWithinPayload() {
        // 총 건수만으로는 중복과 누락의 교환을 검출하지 못함.
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
        // 필수 필드 누락이면 파일 전체가 400이 됨.
        // 원본 계약의 0건을 고정.
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
        // 정규화 후 표기가 사라지므로 원본에서 입력 형태를 확인.
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
        // 소수 걸음수 보존이 집계 회귀값의 전제.
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
        // DB 반올림과 해시 대상의 불일치 방지.
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
        // 오프셋을 무시하면 서울 기준 집계 날짜가 하루 밀림.
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
        // 정규화와 집계 중 어느 단계에서 회귀가 어긋나는지 분리해 진단.
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

        // 키 집합까지 비교해 삭제·복제 교환을 검출.
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
