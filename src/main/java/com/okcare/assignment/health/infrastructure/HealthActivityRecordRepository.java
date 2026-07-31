package com.okcare.assignment.health.infrastructure;

import com.okcare.assignment.health.domain.DailyTotal;
import com.okcare.assignment.health.domain.HealthActivityRecord;
import com.okcare.assignment.health.domain.MonthlyTotal;
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
     * batch의 측정 구간 시작 시각으로 기존 레코드 조회.
     *
     * <p>나머지 식별자까지 IN 조건으로 묶으면 조합이 커짐.
     * 호출부가 최종 식별자를 대조.
     * {@code ix_health_activity_records_connection_period}가 이 조회의 인덱스.
     */
    List<HealthActivityRecord> findByConnectionIdAndPeriodStartUtcIn(
            Long connectionId, Collection<Instant> periodStarts);

    /**
     * 날짜별 반올림 전 합계.
     *
     * <p>{@code activity_date}와 연결 ID 조건이 복합 인덱스 순서와 같음.
     * 빈 날짜와 결과 정렬은 호출부가 처리.
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

    /**
     * 월별 반올림 전 합계.
     *
     * <p>일별 결과를 다시 더하지 않고 원본 행을 직접 집계.
     * 반올림 오차 방지.
     * 범위는 호출부가 시작일과 종료일로 변환해 전달.
     */
    @Query(
            """
            select new com.okcare.assignment.health.domain.MonthlyTotal(
                    year(record.activityDate),
                    month(record.activityDate),
                    sum(record.steps),
                    sum(record.calories),
                    sum(record.distance))
            from HealthActivityRecord record
            where record.connectionId = :connectionId
              and record.activityDate between :from and :to
            group by year(record.activityDate), month(record.activityDate)
            """)
    List<MonthlyTotal> sumMonthlyTotals(
            @Param("connectionId") Long connectionId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);
}
