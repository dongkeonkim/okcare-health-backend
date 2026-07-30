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
 * <p>활동 레코드 저장과 다른 트랜잭션이어야 해서 별도 빈. 같은 빈 안의 메서드로 두면 자기 호출이
 * 프록시를 우회해 트랜잭션이 하나로 합쳐지고, 아래 재시도가 조용히 동작하지 않음.
 *
 * <p>연결만 만들어지고 이어지는 레코드 저장이 실패하면 레코드가 없는 연결이 남음. 다음 요청이 같은
 * 연결을 재사용하므로 보정하지 않음.
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
     * <p>엔티티가 아니라 식별자를 돌려줌. 호출부의 트랜잭션에서는 이 엔티티가 준영속이라
     * 그대로 쓰면 다루기 까다로움.
     *
     * <p>같은 회원이 새 {@code recordkey}로 동시에 두 번 저장하면 한쪽이 UNIQUE 위반을 만남.
     * 그 트랜잭션은 이미 롤백 대상이라 여기서 재조회할 수 없으므로 호출부가 이 메서드를 다시
     * 부르고, 두 번째 호출은 새 트랜잭션에서 승자가 만든 연결을 찾음.
     *
     * @throws BusinessException 다른 회원이 소유한 {@code recordkey}일 때
     * @throws ConcurrentCreationException 동시 생성으로 UNIQUE 위반이 났을 때. 호출부가 재시도
     */
    @Transactional
    public long resolve(long memberId, NormalizedPayload payload) {
        Optional<HealthConnection> found = repository.findByRecordKey(payload.recordKey());

        if (found.isPresent()) {
            return requireOwner(found.get(), memberId);
        }

        try {
            // 명시적 flush로 제약 위반을 이 catch 경계 안에서 관찰. flush 시점이 ID 전략에
            // 좌우되지 않으므로 채번 방식이 바뀌어도 동시 생성이 500으로 새지 않음.
            return repository.saveAndFlush(HealthConnection.create(memberId, payload)).getId();
        } catch (DataIntegrityViolationException e) {
            // health_connections의 UNIQUE 제약은 uk_health_connections_record_key 하나뿐이라
            // 원인이 특정됨.
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
