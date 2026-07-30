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
 *
 * <p>DB 트랜잭션 밖에서 호출. 파싱과 해시 계산은 요청 크기에 비례해 시간이 걸리므로 트랜잭션에
 * 넣으면 커넥션을 그만큼 오래 잡음.
 *
 * <p>Bean Validation이 걸러내지 못하는 실패만 여기서 봄. 필드 존재와 길이는 요청 DTO의 제약이
 * 이미 검사하고 {@code fieldErrors}가 달린 400을 만듦. 여기서 나오는 400에는 필드 목록이 없음.
 */
@Component
public class HealthDataNormalizer {

    private static final String CALORIES_UNIT = "kcal";
    private static final String DISTANCE_UNIT = "km";

    /**
     * 측정값 저장 소수 자릿수. {@code health_activity_records}의 {@code DECIMAL(24, 12)}와 같은 값.
     *
     * <p>MySQL의 암묵적 반올림에 맡기지 않고 여기서 명시적으로 맞춤. 맡기면 저장값과
     * {@code payloadHash}의 대상이 달라져, 12자리 뒤만 다른 재전송이 행은 그대로인데
     * {@code updated}로 세어짐.
     *
     * <p>12자리로 충분한 근거는 출력 계약. 집계 응답은 걸음수를 정수, 칼로리·거리를 소수점 여섯
     * 자리로 반올림하므로 한 달 최대 757건을 더해도 누적 오차가 출력 단위보다 세 자릿수 이상 작음.
     */
    private static final int STORED_SCALE = 12;

    /** {@code DECIMAL(24, 12)}의 정수부 자릿수. 초과하면 MySQL이 범위 초과로 거부해 500이 됨. */
    private static final int MAX_INTEGER_DIGITS = 12;

    /** 저장 가능한 절댓값의 상한. 이 값 자체는 정수부가 한 자리 늘어나므로 포함하지 않음. */
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
     * 저장 정밀도로 맞춤.
     *
     * <p>소수 자릿수는 제약하지 않고 반올림. 공급자가 보내는 20자리는 총합을 구간에 나눌 때 생긴
     * 부동소수점 잡음이라 거절할 이유가 없고, 거절하면 과제가 준 입력이 400이 됨.
     *
     * <p>정수부는 거절. MySQL이 범위 초과로 insert를 실패시켜 400이어야 할 것이 500이 됨.
     *
     * <p>자릿수를 {@code precision() - scale()}로 세지 않고 절댓값을 비교. 표현 속성으로 세면 세
     * 가지가 어긋남. 0은 {@code precision}이 항상 1이라 {@code 0E+13}처럼 음수 scale이면 없는
     * 정수부가 생겨 저장 가능한 값을 거절. 아주 큰 음수 scale에서는 뺄셈 자체가 overflow.
     *
     * <p>양자화 뒤에 한 번 더 검사. {@code 999999999999.9999999999995}는 검사 시점에 12자리인데
     * 반올림 carry로 13자리가 되어, 통과시키면 막으려던 500이 그대로 발생.
     */
    private static BigDecimal toStoredScale(BigDecimal value) {
        requireWithinRange(value);

        BigDecimal quantized;
        try {
            quantized = value.setScale(STORED_SCALE, RoundingMode.HALF_UP);
        } catch (ArithmeticException e) {
            // 1E-999999999처럼 절댓값은 작지만 scale이 극단적으로 큰 값. 범위 검사는 통과하고
            // setScale이 내부 BigInteger 한계를 넘어 실패함. 잡지 않으면 400이 500이 됨.
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
            // 원본 문자열을 예외에 담지 않음. 오류 응답과 로그로 요청 payload가 새어 나감.
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
     * 측정값 변경 감지용 해시.
     *
     * <p>공급자가 알린 갱신 시각을 넣지 않음. 넣으면 측정값이 같은 재전송이 변경으로 판정됨.
     *
     * <p>{@code stripTrailingZeros}로 표기를 통일. {@code 54}와 {@code 54.0}은 같은 값인데
     * {@code toString}이 달라, 통일하지 않으면 공급자가 표기만 바꿔도 변경으로 읽힘.
     *
     * <p>저장 정밀도로 맞춘 값에서 계산. 원본에서 계산하면 12자리 뒤만 다른 재전송이 저장 행은
     * 그대로인데 {@code updated}로 세어짐.
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
