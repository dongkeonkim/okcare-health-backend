package com.okcare.assignment.health.application;

import com.okcare.assignment.common.error.BusinessException;
import com.okcare.assignment.common.error.ErrorCode;
import com.okcare.assignment.common.security.Sha256;
import com.okcare.assignment.config.AppProperties;
import com.okcare.assignment.health.api.HealthDataRequest;
import com.okcare.assignment.health.domain.HealthProvider;
import com.okcare.assignment.health.domain.NormalizedPayload;
import com.okcare.assignment.health.domain.NormalizedRecord;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 공급자별 차이를 내부 모델로 흡수.
 * 파싱과 해시는 DB 트랜잭션 밖에서 수행.
 */
@Component
public class HealthDataNormalizer {

    private static final String CALORIES_UNIT = "kcal";
    private static final String DISTANCE_UNIT = "km";

    /** 저장소 {@code DECIMAL(24, 12)}와 맞추는 측정값 소수 자릿수. */
    private static final int STORED_SCALE = 12;

    /** {@code DECIMAL(24, 12)}의 정수부 자릿수. 초과하면 MySQL이 범위 초과로 거부해 500이 됨. */
    private static final int MAX_INTEGER_DIGITS = 12;

    /** 저장 가능한 절댓값의 배타적 상한. */
    private static final BigDecimal EXCLUSIVE_LIMIT = BigDecimal.TEN.pow(MAX_INTEGER_DIGITS);

    private final ZoneId businessZone;

    public HealthDataNormalizer(AppProperties appProperties) {
        this.businessZone = appProperties.businessZone();
    }

    /**
     * @throws BusinessException 미지원 공급자, 지원하지 않는 단위, 파싱할 수 없는 시각일 때
     */
    public NormalizedPayload normalize(HealthDataRequest request) {
        HealthProvider provider =
                HealthProvider.bySourceName(request.data().source().name())
                        .orElseThrow(() -> new BusinessException(ErrorCode.HEALTH_DATA_INVALID));

        List<NormalizedRecord> records = new ArrayList<>(request.data().entries().size());
        for (HealthDataRequest.Entry entry : request.data().entries()) {
            records.add(toRecord(provider, request.type(), entry));
        }

        return new NormalizedPayload(
                request.recordkey(),
                request.data().source().name(),
                request.data().source().product().name(),
                request.data().source().product().vender(),
                request.data().source().mode(),
                parseLastUpdate(request.lastUpdate()),
                List.copyOf(records));
    }

    private NormalizedRecord toRecord(
            HealthProvider provider, String metricType, HealthDataRequest.Entry entry) {

        requireUnit(entry.calories().unit(), CALORIES_UNIT);
        requireUnit(entry.distance().unit(), DISTANCE_UNIT);

        Instant start = toInstant(provider, entry.period().from());
        Instant end = toInstant(provider, entry.period().to());

        BigDecimal steps = toStoredScale(entry.steps());
        BigDecimal calories = toStoredScale(entry.calories().value());
        BigDecimal distance = toStoredScale(entry.distance().value());

        return new NormalizedRecord(
                metricType,
                start,
                end,
                // 시작 시각의 날짜. 시작과 종료가 같은 구간도 버리지 않고 이 날짜에 포함.
                start.atZone(businessZone).toLocalDate(),
                steps,
                calories,
                distance,
                entry.calories().unit(),
                entry.distance().unit(),
                payloadHash(
                        steps, calories, distance,
                        entry.calories().unit(), entry.distance().unit()));
    }

    /**
     * 저장 정밀도로 양자화하고 범위를 검사.
     *
     * <p>소수부는 반올림하고 정수부 초과는 애플리케이션에서 거부.
     * 절댓값과 반올림 carry를 모두 검사해 DB 범위 오류의 500 노출 방지.
     */
    private static BigDecimal toStoredScale(BigDecimal value) {
        requireWithinRange(value);

        BigDecimal quantized;
        try {
            quantized = value.setScale(STORED_SCALE, RoundingMode.HALF_UP);
        } catch (ArithmeticException e) {
            // 극단적으로 큰 scale에서 setScale이 실패해도 요청 오류로 변환.
            throw new BusinessException(ErrorCode.HEALTH_DATA_INVALID);
        }

        requireWithinRange(quantized);

        return quantized;
    }

    private static void requireWithinRange(BigDecimal value) {
        if (value.abs().compareTo(EXCLUSIVE_LIMIT) >= 0) {
            throw new BusinessException(ErrorCode.HEALTH_DATA_INVALID);
        }
    }

    private Instant toInstant(HealthProvider provider, String value) {
        try {
            return provider.toInstant(value, businessZone);
        } catch (DateTimeParseException e) {
            // 원본 문자열은 오류 응답과 로그에 남기지 않음.
            throw new BusinessException(ErrorCode.HEALTH_DATA_INVALID);
        }
    }

    private Instant parseLastUpdate(String value) {
        try {
            return HealthProvider.parseLastUpdate(value);
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.HEALTH_DATA_INVALID);
        }
    }

    private static void requireUnit(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new BusinessException(ErrorCode.HEALTH_DATA_INVALID);
        }
    }

    /**
     * 측정값과 단위를 저장 정밀도로 정규화해 변경 감지용 해시 생성.
     * 갱신 시각은 제외.
     */
    private static String payloadHash(
            BigDecimal steps,
            BigDecimal calories,
            BigDecimal distance,
            String caloriesUnit,
            String distanceUnit) {

        String canonical =
                String.join(
                        "|",
                        canonical(steps),
                        canonical(calories),
                        canonical(distance),
                        caloriesUnit,
                        distanceUnit);

        return Sha256.hex(canonical);
    }

    private static String canonical(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
