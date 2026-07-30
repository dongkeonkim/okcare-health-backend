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
     * 회원이 소유한 연결만 조회.
     *
     * <p>조회 API는 {@code recordkey}가 없을 때와 남의 것일 때를 같은 404로 응답. 두 경우를
     * 구분하지 않으므로 회원 조건을 조회에 함께 걸어 한 번으로 끝냄. 저장 경로처럼 찾은 뒤 소유권을
     * 비교하면 구분할 수 있게 되고, 구분한 정보를 응답에서 다시 버려야 함.
     */
    Optional<HealthConnection> findByRecordKeyAndMemberId(String recordKey, Long memberId);

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
