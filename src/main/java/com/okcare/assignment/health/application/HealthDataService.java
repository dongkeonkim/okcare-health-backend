package com.okcare.assignment.health.application;

import com.okcare.assignment.common.error.BusinessException;
import com.okcare.assignment.health.api.HealthDataRequest;
import com.okcare.assignment.health.domain.NormalizedPayload;
import com.okcare.assignment.health.domain.SaveResult;
import org.springframework.stereotype.Service;

/**
 * 건강활동 데이터 저장 조립.
 *
 * <p>{@code @Transactional}을 붙이지 않음. 연결 해석과 레코드 저장이 서로 다른 트랜잭션이어야
 * 하므로 여기서 트랜잭션을 열면 둘이 하나로 합쳐짐. 정규화도 트랜잭션 밖에서 끝나야 함.
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

    /**
     * 동시 생성으로 UNIQUE 위반이 나면 한 번 다시 시도.
     *
     * <p>재시도는 대개 조회로 끝남. 앞선 요청이 커밋했으면 그 행이 보이므로 삽입하지 않음.
     *
     * <p>승자가 되돌아가고 그 틈에 또 다른 요청이 같은 {@code recordkey}를 만들면 재시도도 같은
     * 예외를 받음. 최초 저장이 동시에 세 번 이상 겹쳐야 하는 조건이라 재시도 횟수를 늘리지 않음.
     * 몇 번이면 충분한지에는 답이 없고, 늘려도 같은 틈이 남음.
     */
    private long resolveConnection(long memberId, NormalizedPayload payload) {
        try {
            return connectionResolver.resolve(memberId, payload);
        } catch (HealthConnectionResolver.ConcurrentCreationException e) {
            return connectionResolver.resolve(memberId, payload);
        }
    }
}
