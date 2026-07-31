package com.okcare.assignment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 회귀 기준 파일을 읽어 테스트와 기대값의 중복을 방지. */
public final class RegressionBaseline {

    private static final Path SPEC = Path.of("docs/명세/기능_명세.md");

    /** 회귀 기준 구간의 경계. */
    private static final Pattern SECTION_START = Pattern.compile("^## 8\\. ");
    private static final Pattern SECTION_END = Pattern.compile("^## ");

    /** 예: {@code - Apple Health 문자열 `steps`: 2,147건} */
    private static final Pattern COUNT_LINE = Pattern.compile("^- (.+?): ([\\d,]+)건$");

    /** 예: {@code | `7836887b-...` | 2024-11 | 124783 | 5002.499439 | 94.342095 |} */
    private static final Pattern MONTHLY_LINE =
            Pattern.compile(
                    "^\\|\\s*`?([0-9a-f-]{36})`?\\s*\\|\\s*(\\d{4}-\\d{2})\\s*"
                            + "\\|\\s*(\\d+)\\s*\\|\\s*([\\d.]+)\\s*"
                            + "\\|\\s*([\\d.]+)\\s*\\|$");

    private final Map<String, Integer> counts;
    private final Map<MonthlyKey, MonthlyTotal> monthlyTotals;

    /** 표의 한 행을 가리키는 키. 중복이 생기면 커버리지가 조용히 줄어듦. */
    public record MonthlyKey(String recordKey, YearMonth month) {}

    /** 집계 출력 계약에 맞춘 값. 걸음수는 정수, 칼로리·거리는 소수점 여섯 자리. */
    public record MonthlyTotal(long steps, BigDecimal calories, BigDecimal distance) {}

    private RegressionBaseline(
            Map<String, Integer> counts, Map<MonthlyKey, MonthlyTotal> monthlyTotals) {
        this.counts = counts;
        this.monthlyTotals = monthlyTotals;
    }

    public static RegressionBaseline load() {
        Map<String, Integer> parsed = new LinkedHashMap<>();
        Map<MonthlyKey, MonthlyTotal> monthly = new LinkedHashMap<>();
        boolean inSection = false;

        for (String line : readSpec()) {
            if (SECTION_START.matcher(line).find()) {
                inSection = true;
                continue;
            }
            if (inSection && SECTION_END.matcher(line).find()) {
                break;
            }
            if (!inSection) {
                continue;
            }

            Matcher count = COUNT_LINE.matcher(line.trim());
            if (count.matches()) {
                // 라벨의 백틱 제거. 테스트가 마크다운 표기를 알지 않아도 되게 함.
                String label = count.group(1).replace("`", "");
                parsed.put(label, Integer.parseInt(count.group(2).replace(",", "")));
                continue;
            }

            Matcher row = MONTHLY_LINE.matcher(line.trim());
            if (row.matches()) {
                MonthlyKey key =
                        new MonthlyKey(row.group(1), YearMonth.parse(row.group(2)));
                MonthlyTotal previous =
                        monthly.put(
                                key,
                                new MonthlyTotal(
                                        Long.parseLong(row.group(3)),
                                        new BigDecimal(row.group(4)),
                                        new BigDecimal(row.group(5))));

                // 같은 키가 두 번 나오면 한 행을 지우고 다른 행을 복제해도 행 수가 유지되어,
                // 호출부의 건수 단언만으로는 커버리지 축소를 잡지 못함.
                if (previous != null) {
                    throw new AssertionError("회귀 기준의 월간 표에 중복된 행이 있습니다: " + key);
                }
            }
        }

        if (parsed.isEmpty()) {
            throw new AssertionError(
                    "회귀 기준에서 입력 특성을 읽지 못했습니다: " + SPEC.toAbsolutePath());
        }

        if (monthly.isEmpty()) {
            throw new AssertionError(
                    "회귀 기준에서 월간 표를 읽지 못했습니다: " + SPEC.toAbsolutePath());
        }

        // LinkedHashMap으로 기준 순서와 첫 항목의 의미를 고정.
        return new RegressionBaseline(
                parsed, Collections.unmodifiableMap(new LinkedHashMap<>(monthly)));
    }

    /** 호출부가 키 집합까지 대조해야 함. 값만 확인하면 빠진 월을 알 수 없음. */
    public Map<MonthlyKey, MonthlyTotal> monthlyTotals() {
        return monthlyTotals;
    }

    /** 라벨이 없으면 읽어 온 목록을 함께 알려 원인을 진단. */
    public int count(String label) {
        Integer value = counts.get(label);

        if (value == null) {
            throw new AssertionError(
                    "회귀 기준에 없는 라벨입니다: "
                            + label
                            + " / 읽어 온 라벨: "
                            + counts.keySet());
        }

        return value;
    }

    private static Iterable<String> readSpec() {
        try {
            return Files.readAllLines(SPEC);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "기능 명세를 읽을 수 없습니다: " + SPEC.toAbsolutePath(), e);
        }
    }
}
