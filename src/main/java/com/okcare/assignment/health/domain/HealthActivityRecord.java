package com.okcare.assignment.health.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 정규화한 건강활동 측정 레코드.
 *
 * <p>{@code connectionId}를 식별자로 둔 이유는 {@link HealthConnection}과 같음.
 *
 * <p>{@code precision}과 {@code scale}을 명시해 두면 스키마와 어긋날 때 Hibernate의
 * {@code ddl-auto: validate}가 기동 시점에 잡음.
 */
@Entity
@Table(name = "health_activity_records")
public class HealthActivityRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "connection_id", nullable = false)
    private Long connectionId;

    @Column(name = "metric_type", nullable = false, length = 32)
    private String metricType;

    @Column(name = "period_start_utc", nullable = false)
    private Instant periodStartUtc;

    @Column(name = "period_end_utc", nullable = false)
    private Instant periodEndUtc;

    @Column(name = "activity_date", nullable = false)
    private LocalDate activityDate;

    @Column(nullable = false, precision = 24, scale = 12)
    private BigDecimal steps;

    @Column(nullable = false, precision = 24, scale = 12)
    private BigDecimal calories;

    @Column(nullable = false, precision = 24, scale = 12)
    private BigDecimal distance;

    @Column(name = "calories_unit", nullable = false, length = 16)
    private String caloriesUnit;

    @Column(name = "distance_unit", nullable = false, length = 16)
    private String distanceUnit;

    @Column(name = "source_last_updated_at", nullable = false)
    private Instant sourceLastUpdatedAt;

    // DDL이 CHAR(64)라 columnDefinition을 명시. 없으면 varchar로 기대해 ddl-auto: validate가
    // 기동을 막음. 길이가 고정된 hex라 CHAR가 맞고, MySQL은 CHAR 조회 시 뒤 공백을 떼므로
    // 패딩 문제도 없음.
    @Column(name = "payload_hash", nullable = false, length = 64, columnDefinition = "char(64)")
    private String payloadHash;

    protected HealthActivityRecord() {}

    private HealthActivityRecord(
            Long connectionId, NormalizedRecord record, Instant sourceLastUpdatedAt) {
        this.connectionId = connectionId;
        this.metricType = record.metricType();
        this.periodStartUtc = record.periodStart();
        this.periodEndUtc = record.periodEnd();
        apply(record, sourceLastUpdatedAt);
    }

    public static HealthActivityRecord create(
            long connectionId, NormalizedRecord record, Instant sourceLastUpdatedAt) {
        return new HealthActivityRecord(connectionId, record, sourceLastUpdatedAt);
    }

    /**
     * 측정값 갱신.
     *
     * <p>식별자 컬럼은 건드리지 않음. 같은 식별자를 찾아 온 레코드이므로 바꿀 것이 측정값과
     * 공급자가 알린 갱신 시각뿐.
     */
    public void apply(NormalizedRecord record, Instant sourceLastUpdatedAt) {
        this.activityDate = record.activityDate();
        this.steps = record.steps();
        this.calories = record.calories();
        this.distance = record.distance();
        this.caloriesUnit = record.caloriesUnit();
        this.distanceUnit = record.distanceUnit();
        this.sourceLastUpdatedAt = sourceLastUpdatedAt;
        this.payloadHash = record.payloadHash();
    }

    public NormalizedRecord.Identity identity() {
        return new NormalizedRecord.Identity(metricType, periodStartUtc, periodEndUtc);
    }

    /** 측정값 변경 판정. 해시 비교이므로 값을 하나하나 다시 비교하지 않음. */
    public boolean hasSameMeasurements(NormalizedRecord record) {
        return payloadHash.equals(record.payloadHash());
    }

    public Long getId() {
        return id;
    }

    public BigDecimal getSteps() {
        return steps;
    }

    public BigDecimal getCalories() {
        return calories;
    }

    public BigDecimal getDistance() {
        return distance;
    }
}
