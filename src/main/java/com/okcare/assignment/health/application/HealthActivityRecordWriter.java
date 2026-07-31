package com.okcare.assignment.health.application;

import com.okcare.assignment.health.domain.HealthActivityRecord;
import com.okcare.assignment.health.domain.NormalizedPayload;
import com.okcare.assignment.health.domain.NormalizedRecord;
import com.okcare.assignment.health.infrastructure.HealthActivityRecordRepository;
import com.okcare.assignment.health.infrastructure.HealthConnectionRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
     * 활동 레코드 멱등 저장.
     *
     * <p>연결 해석과 별도 트랜잭션에서 사전 분류.
     * 동시 요청은 UNIQUE 제약으로 최종 보장.
     */
@Service
public class HealthActivityRecordWriter {

    /**
     * 영속성 컨텍스트와 IN 조건 크기를 제한하는 batch 크기.
     *
     * <p>AUTO_INCREMENT insert batch는 사용하지 않음.
     */
    private static final int BATCH_SIZE = 500;

    private final HealthActivityRecordRepository repository;
    private final HealthConnectionRepository connectionRepository;
    private final EntityManager entityManager;

    public HealthActivityRecordWriter(
            HealthActivityRecordRepository repository,
            HealthConnectionRepository connectionRepository,
            EntityManager entityManager) {
        this.repository = repository;
        this.connectionRepository = connectionRepository;
        this.entityManager = entityManager;
    }

    /**
     * 요청 내 중복을 접고 batch 단위로 저장.
     *
     * @return 식별자 기준 분류 결과. 요청 엔트리 수는 호출부가 채움
     */
    @Transactional
    public Counts write(long connectionId, NormalizedPayload payload) {
        // 같은 연결의 저장을 직렬화해 활동 식별자 UNIQUE 위반 방지.
        connectionRepository.findByIdForUpdate(connectionId);

        Counts counts = new Counts();

        for (List<NormalizedRecord> batch : batches(collapseDuplicates(payload))) {
            Map<NormalizedRecord.Identity, HealthActivityRecord> existing =
                    findExisting(connectionId, batch);

            for (NormalizedRecord record : batch) {
                HealthActivityRecord found = existing.get(record.identity());

                if (found == null) {
                    repository.save(
                            HealthActivityRecord.create(
                                    connectionId, record, payload.sourceLastUpdatedAt()));
                    counts.inserted++;
                } else if (found.hasSameMeasurements(record)) {
                    counts.duplicated++;
                } else {
                    found.apply(record, payload.sourceLastUpdatedAt());
                    counts.updated++;
                }
            }

            // batch마다 flush와 clear로 더티 체크 범위 제한.
            entityManager.flush();
            entityManager.clear();
        }

        return counts;
    }

    /**
     * 요청 내 동일 식별자 접기.
     * 마지막 측정값과 첫 등장 순서 유지.
     */
    private static List<NormalizedRecord> collapseDuplicates(NormalizedPayload payload) {
        Map<NormalizedRecord.Identity, NormalizedRecord> byIdentity = new LinkedHashMap<>();

        for (NormalizedRecord record : payload.records()) {
            byIdentity.put(record.identity(), record);
        }

        return List.copyOf(byIdentity.values());
    }

    private Map<NormalizedRecord.Identity, HealthActivityRecord> findExisting(
            long connectionId, List<NormalizedRecord> batch) {

        List<Instant> starts =
                batch.stream().map(NormalizedRecord::periodStart).distinct().toList();
        Map<NormalizedRecord.Identity, HealthActivityRecord> byIdentity = new HashMap<>();

        for (HealthActivityRecord found :
                repository.findByConnectionIdAndPeriodStartUtcIn(connectionId, starts)) {
            byIdentity.put(found.identity(), found);
        }

        return byIdentity;
    }

    private static List<List<NormalizedRecord>> batches(List<NormalizedRecord> records) {
        List<List<NormalizedRecord>> batches = new ArrayList<>();

        for (int from = 0; from < records.size(); from += BATCH_SIZE) {
            batches.add(records.subList(from, Math.min(from + BATCH_SIZE, records.size())));
        }

        return batches;
    }

    /** 분류 결과. 요청 엔트리 수를 모르므로 {@code SaveResult}를 직접 만들지 않음. */
    public static final class Counts {

        private int inserted;
        private int updated;
        private int duplicated;

        public int inserted() {
            return inserted;
        }

        public int updated() {
            return updated;
        }

        public int duplicated() {
            return duplicated;
        }
    }
}
