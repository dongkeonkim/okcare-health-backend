package com.okcare.assignment.health.infrastructure;

import com.okcare.assignment.health.domain.HealthConnection;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HealthConnectionRepository extends JpaRepository<HealthConnection, Long> {

    /**
     * {@code recordkey}로 연결 조회.
     * 저장 경로에서 회원 소유 여부를 별도 판정.
     */
    Optional<HealthConnection> findByRecordKey(String recordKey);

    /**
     * 조회 API에서 없는 키와 타인 소유 키를 같은 404로 감춤.
     * 회원 소유 조회.
     */
    Optional<HealthConnection> findByRecordKeyAndMemberId(String recordKey, Long memberId);

    /**
     * 활동 레코드 저장 전 연결 행을 잠가 같은 {@code recordkey} 저장을 직렬화.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select connection from HealthConnection connection where connection.id = :id")
    Optional<HealthConnection> findByIdForUpdate(@Param("id") Long id);
}
