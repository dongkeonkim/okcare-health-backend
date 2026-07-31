package com.okcare.assignment.health.application;

import com.okcare.assignment.common.error.BusinessException;
import com.okcare.assignment.common.error.ErrorCode;
import com.okcare.assignment.health.domain.HealthConnection;
import com.okcare.assignment.health.domain.NormalizedPayload;
import com.okcare.assignment.health.infrastructure.HealthConnectionRepository;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
     * {@code recordkey}와 회원의 연결 해석.
     *
     * <p>활동 레코드 저장과 트랜잭션을 분리.
     * 동시 생성 UNIQUE 위반은 새 트랜잭션에서 재시도.
     */
@Service
public class HealthConnectionResolver {

    private final HealthConnectionRepository repository;

    public HealthConnectionResolver(HealthConnectionRepository repository) {
        this.repository = repository;
    }

    /**
     * 연결 식별자 반환. 없으면 인증된 회원 소유로 생성.
     *
     * <p>동시 생성 UNIQUE 위반은 호출부가 새 트랜잭션에서 재시도.
     *
     * @throws BusinessException 다른 회원이 소유한 {@code recordkey}일 때
     * @throws ConcurrentCreationException 동시 생성으로 UNIQUE 위반이 났을 때
     */
    @Transactional
    public long resolve(long memberId, NormalizedPayload payload) {
        Optional<HealthConnection> found = repository.findByRecordKey(payload.recordKey());

        if (found.isPresent()) {
            return requireOwner(found.get(), memberId);
        }

        try {
            // 명시적 flush로 제약 위반을 이 catch 경계 안에서 관찰.
            return repository.saveAndFlush(HealthConnection.create(memberId, payload)).getId();
        } catch (DataIntegrityViolationException e) {
            // 정상 입력에서 발생 가능한 제약 위반을 동시 recordkey 생성으로 분류.
            throw new ConcurrentCreationException();
        }
    }

    private static long requireOwner(HealthConnection connection, long memberId) {
        if (!connection.ownedBy(memberId)) {
            throw new BusinessException(ErrorCode.HEALTH_RECORD_KEY_CONFLICT);
        }

        return connection.getId();
    }

    /** 재시도 신호. 클라이언트에 노출되지 않으므로 오류 코드를 갖지 않음. */
    public static class ConcurrentCreationException extends RuntimeException {

        ConcurrentCreationException() {
            super("연결이 동시에 생성되었습니다.");
        }
    }
}
