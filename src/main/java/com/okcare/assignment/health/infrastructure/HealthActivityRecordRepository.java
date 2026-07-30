package com.okcare.assignment.health.infrastructure;

import com.okcare.assignment.health.domain.HealthActivityRecord;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HealthActivityRecordRepository
        extends JpaRepository<HealthActivityRecord, Long> {

    /**
     * batch에 담긴 측정 구간 시작 시각으로 기존 레코드 조회.
     *
     * <p>식별자 네 컬럼을 모두 걸지 않고 시작 시각으로만 좁힘. 나머지까지 IN 조건으로 엮으면 조합
     * 폭발이 생김. 한 시작 시각에 종료 시각이 다른 행이 여럿 있을 수 있어 결과가 batch 크기를 넘을
     * 수 있고, 호출부가 식별자로 다시 걸러냄.
     * {@code ix_health_activity_records_connection_period}가 이 조회의 인덱스.
     */
    List<HealthActivityRecord> findByConnectionIdAndPeriodStartUtcIn(
            Long connectionId, Collection<Instant> periodStarts);

    long countByConnectionId(Long connectionId);
}
