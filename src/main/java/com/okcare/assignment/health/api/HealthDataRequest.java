package com.okcare.assignment.health.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * 건강활동 데이터 저장 요청.
 *
 * <p>공급자가 보내는 JSON 구조를 그대로 받음. 필드명 {@code recordkey}의 소문자 k와
 * {@code vender} 오탈자는 둘 다 공급자 원본 계약이라 고치지 않음. 저장 응답은 기능 명세가
 * {@code recordKey}로 다르게 정하므로 이름이 어긋나는 것이 정상.
 *
 * <p>길이 상한은 {@code health_connections}와 {@code health_activity_records}의 컬럼 정의와 같은
 * 값. 검증을 빼면 컬럼 폭을 넘는 값이 400이 아니라 저장 시점의 500으로 드러남.
 *
 * <p>{@code data.memo}와 {@code source.type}은 받지 않음. 저장할 컬럼이 없음.
 */
public record HealthDataRequest(
        @NotBlank(message = "recordkey는 필수입니다.")
        @Size(min = 36, max = 36, message = "recordkey는 36자여야 합니다.")
        String recordkey,

        @NotBlank(message = "type은 필수입니다.")
        @Pattern(regexp = "steps", message = "지원하지 않는 지표입니다.")
        String type,

        @NotBlank(message = "lastUpdate는 필수입니다.")
        String lastUpdate,

        @NotNull(message = "data는 필수입니다.")
        @Valid
        Data data
) {

    /**
     * 검증 전 {@code type} 정규화.
     *
     * <p>{@link Pattern}이 정규화 이후 값을 보도록 여기서 먼저 다듬음. 앞뒤 공백과 대소문자만
     * 다른 입력을 형식 오류로 거절하지 않기 위함.
     */
    public HealthDataRequest {
        if (type != null) {
            type = type.trim().toLowerCase(Locale.ROOT);
        }
    }

    public record Data(
            @NotNull(message = "source는 필수입니다.")
            @Valid
            Source source,

            @NotEmpty(message = "entries는 비어 있을 수 없습니다.")
            @Valid
            List<Entry> entries
    ) {}

    public record Source(
            @NotBlank(message = "source.name은 필수입니다.")
            @Size(max = 64, message = "source.name은 64자를 넘을 수 없습니다.")
            String name,

            @NotNull(message = "source.mode는 필수입니다.")
            Integer mode,

            @NotNull(message = "source.product는 필수입니다.")
            @Valid
            Product product
    ) {}

    public record Product(
            @NotBlank(message = "product.name은 필수입니다.")
            @Size(max = 64, message = "product.name은 64자를 넘을 수 없습니다.")
            String name,

            @NotBlank(message = "product.vender는 필수입니다.")
            @Size(max = 64, message = "product.vender는 64자를 넘을 수 없습니다.")
            String vender
    ) {}

    public record Entry(
            @NotNull(message = "period는 필수입니다.")
            @Valid
            Period period,

            @NotNull(message = "steps는 필수입니다.")
            BigDecimal steps,

            @NotNull(message = "calories는 필수입니다.")
            @Valid
            Measure calories,

            @NotNull(message = "distance는 필수입니다.")
            @Valid
            Measure distance
    ) {}

    public record Period(
            @NotBlank(message = "period.from은 필수입니다.")
            String from,

            @NotBlank(message = "period.to는 필수입니다.")
            String to
    ) {}

    /**
     * 측정값과 단위.
     *
     * <p>{@link BigDecimal}로 받는 이유는 두 가지. JSON number를 double로 받으면 정밀도가 깨짐.
     * 그리고 Health Kit의 문자열 {@code steps}까지 같은 타입으로 흡수 가능.
     */
    public record Measure(
            @NotNull(message = "value는 필수입니다.")
            BigDecimal value,

            @NotBlank(message = "unit은 필수입니다.")
            @Size(max = 16, message = "unit은 16자를 넘을 수 없습니다.")
            String unit
    ) {}
}
