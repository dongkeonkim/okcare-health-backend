package com.okcare.assignment.health.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;

/**
 * 회원과 {@code recordkey}의 소유권 연결.
 *
 * <p>{@code memberId}를 {@code @ManyToOne}이 아니라 식별자로 둠. 소유권 판정에 필요한 것이 식별자
 * 비교뿐이라 연관 엔티티를 두면 회원 행을 읽는 비용만 늘어남. 참조 정합성은
 * {@code fk_health_connections_member}가 지킴.
 *
 * <p>{@code created_at}과 {@code updated_at}은 DDL 기본값이 채우므로 매핑하지 않음.
 */
@Entity
@Table(name = "health_connections")
public class HealthConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    // DDL이 CHAR(36). 이유는 HealthActivityRecord의 payload_hash와 같음.
    @Column(name = "record_key", nullable = false, length = 36, columnDefinition = "char(36)")
    private String recordKey;

    @Column(name = "source_name", nullable = false, length = 64)
    private String sourceName;

    @Column(name = "product_name", nullable = false, length = 64)
    private String productName;

    @Column(name = "vendor_name", nullable = false, length = 64)
    private String vendorName;

    @Column(name = "source_mode", nullable = false)
    private Integer sourceMode;

    protected HealthConnection() {}

    private HealthConnection(
            Long memberId,
            String recordKey,
            String sourceName,
            String productName,
            String vendorName,
            Integer sourceMode) {
        this.memberId = memberId;
        this.recordKey = recordKey;
        this.sourceName = sourceName;
        this.productName = productName;
        this.vendorName = vendorName;
        this.sourceMode = sourceMode;
    }

    /** 최초 저장 요청을 보낸 회원에게 귀속. */
    public static HealthConnection create(long memberId, NormalizedPayload payload) {
        return new HealthConnection(
                memberId,
                Objects.requireNonNull(payload.recordKey(), "recordKey"),
                payload.sourceName(),
                payload.productName(),
                payload.vendorName(),
                payload.sourceMode());
    }

    /** {@code longValue()}를 명시. 그냥 비교하면 읽는 사람이 참조 비교로 오독. */
    public boolean ownedBy(long memberId) {
        return this.memberId.longValue() == memberId;
    }

    public Long getId() {
        return id;
    }
}
