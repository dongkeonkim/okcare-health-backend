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
 * <p>연결 해석과 다른 트랜잭션이어야 해서 별도 빈. 이유는 {@link HealthConnectionResolver}에 있음.
 *
 * <p>{@code INSERT ... ON DUPLICATE KEY UPDATE}의 affected rows로 카운트를 얻지 않음. 그 값은
 * 드라이버 설정과 {@code ON UPDATE CURRENT_TIMESTAMP}의 무변경 판정에 좌우되어, 응답 카운트의
 * 정확성을 프레임워크 세부에 맡기게 됨. 사전 조회로 분류하면 카운트가 명시적이고, 동시 요청이
 * 만든 중복은 UNIQUE 제약이 최종적으로 막음.
 */
@Service
public class HealthActivityRecordWriter {

    /**
     * 한 번에 조회·저장할 레코드 수.
     *
     * <p>배치를 나누는 목적은 IN 조건의 크기와 영속성 컨텍스트가 무한히 커지지 않게 하는 것.
     * {@code id}가 {@code AUTO_INCREMENT}라 Hibernate가 insert 문 배치를 비활성화하므로, 이 값을
     * 키워도 왕복 횟수는 줄지 않음.
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
        // 같은 연결에 동시에 저장하는 요청을 직렬화. 잠그지 않으면 두 요청이 서로 커밋 전에 기존
        // 레코드를 조회해 양쪽이 신규로 분류하고, 패자가 활동 식별자 UNIQUE 위반을 받아 정상
        // 재전송이 500으로 나감. 위반을 잡아 재시도하는 방법도 있지만 몇 번이면 충분한지가 동시
        // 요청 수에 달려 답이 없음. 같은 recordkey의 저장은 본래 직렬이어도 되는 작업.
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

            // flush만으로는 관리 대상이 그대로 남아 다음 flush의 더티 체크 범위가 계속 커짐.
            // clear까지 해야 batch로 나눈 의미가 있음. 이 시점에 갱신을 이미 반영했으므로 준영속이
            // 되어도 잃는 변경이 없음.
            entityManager.flush();
            entityManager.clear();
        }

        return counts;
    }

    /**
     * 같은 식별자가 여러 번 들어오면 마지막 항목만 남김.
     *
     * <p>마지막 값이 남는 것은 반복 {@code put}이 덮어쓰기 때문. {@code LinkedHashMap}은 처리
     * 순서를 첫 등장 순서로 고정하는 역할만 하며, 그래야 batch 분할 결과가 요청마다 흔들리지 않음.
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
