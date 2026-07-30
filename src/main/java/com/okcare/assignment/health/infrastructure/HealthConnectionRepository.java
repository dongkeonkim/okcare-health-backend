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
     *
     * <p>회원 조건을 걸지 않음. 다른 회원 소유일 때 409로 알려야 하므로 조회 단계에서 걸러내면
     * 소유권 충돌과 최초 저장을 구분할 수 없음.
     */
    Optional<HealthConnection> findByRecordKey(String recordKey);

    /**
     * 활동 레코드를 쓰기 전에 연결 행을 잠금.
     *
     * <p>같은 연결에 동시에 저장하는 요청을 직렬화하는 수단. 잠그지 않으면 두 요청이 서로 커밋 전에
     * 기존 레코드를 조회해 양쪽이 신규로 분류하고, 패자가 활동 식별자 UNIQUE 위반을 받음.
     *
     * <p>{@code findById}에 잠금을 붙이지 않고 별도 이름을 둠. 붙이면 소유권 조회처럼 잠금이 필요
     * 없는 경로까지 행을 잠금.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select connection from HealthConnection connection where connection.id = :id")
    Optional<HealthConnection> findByIdForUpdate(@Param("id") Long id);
}
