package com.okcare.assignment.health.application;

import com.okcare.assignment.common.error.BusinessException;
import com.okcare.assignment.health.api.HealthDataRequest;
import com.okcare.assignment.health.domain.NormalizedPayload;
import com.okcare.assignment.health.domain.SaveResult;
import org.springframework.stereotype.Service;

/**
 * 건강활동 데이터 저장 조립.
 * 연결 해석·정규화·레코드 저장의 트랜잭션 경계를 유지하기 위해
 * 비트랜잭션으로 동작.
 */
@Service
public class HealthDataService {

    private final HealthDataNormalizer normalizer;
    private final HealthConnectionResolver connectionResolver;
    private final HealthActivityRecordWriter recordWriter;

    public HealthDataService(
            HealthDataNormalizer normalizer,
            HealthConnectionResolver connectionResolver,
            HealthActivityRecordWriter recordWriter) {
        this.normalizer = normalizer;
        this.connectionResolver = connectionResolver;
        this.recordWriter = recordWriter;
    }

    /**
     * @throws BusinessException 정규화할 수 없는 요청이거나 다른 회원 소유의 {@code recordkey}일 때
     */
    public SaveResult save(long memberId, HealthDataRequest request) {
        NormalizedPayload payload = normalizer.normalize(request);
        long connectionId = resolveConnection(memberId, payload);
        HealthActivityRecordWriter.Counts counts = recordWriter.write(connectionId, payload);

        return new SaveResult(
                payload.received(), counts.inserted(), counts.updated(), counts.duplicated());
    }

    /** 동시 생성 UNIQUE 위반 시 새 트랜잭션에서 연결을 한 번 재조회. */
    private long resolveConnection(long memberId, NormalizedPayload payload) {
        try {
            return connectionResolver.resolve(memberId, payload);
        } catch (HealthConnectionResolver.ConcurrentCreationException e) {
            return connectionResolver.resolve(memberId, payload);
        }
    }
}
