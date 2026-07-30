package com.okcare.assignment.health.infrastructure;

import com.okcare.assignment.health.domain.DailyTotal;
import com.okcare.assignment.health.domain.HealthActivityRecord;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * 날짜별 측정값 합계. 반올림하지 않은 값을 돌려줌.
     *
     * <p>{@code activity_date}는 저장 시점에 사업 기준 타임존으로 확정해 둔 컬럼이라 조회에서
     * 타임존을 다시 계산하지 않음. {@code ix_health_activity_records_connection_date}가 이 조회의
     * 인덱스이고 조건 두 개가 그 컬럼 순서와 같음.
     *
     * <p>{@code metric_type}을 조건에 걸지 않음. 저장이 {@code steps}만 받으므로 모든 행이 같은
     * 지표이고, 조건을 더하면 인덱스에 없는 컬럼이 끼어듦. 다른 지표를 저장하기로 바꾸는 순간 이
     * 합계가 지표를 섞으므로 그때 인덱스와 조건을 함께 고쳐야 함.
     *
     * <p>정렬을 걸지 않음. 데이터가 없는 날짜를 채우는 호출부가 범위를 훑으면서 순서를 정함.
     */
    @Query(
            """
            select new com.okcare.assignment.health.domain.DailyTotal(
                    record.activityDate,
                    sum(record.steps),
                    sum(record.calories),
                    sum(record.distance))
            from HealthActivityRecord record
            where record.connectionId = :connectionId
              and record.activityDate between :from and :to
            group by record.activityDate
            """)
    List<DailyTotal> sumDailyTotals(
            @Param("connectionId") Long connectionId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);
}
