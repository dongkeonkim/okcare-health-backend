package com.okcare.assignment.member.infrastructure;

import com.okcare.assignment.member.domain.Member;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 회원 영속화.
 *
 * <p>가입 시 중복을 미리 확인하는 데 {@link #findByEmail}을 쓰지 않음. 동시 요청에서는 양쪽 모두
 * 사전 조회를 통과해 중복이 생기므로, 중복 판정은 {@code uk_members_email} 제약에 위임.
 */
public interface MemberRepository extends JpaRepository<Member, Long> {

    /**
     * 로그인 대상 회원 조회.
     *
     * @param email {@link Member#normalizeEmail(String)}을 거친 값. 저장된 값이 정규화 형태이므로
     *     원본을 넘기면 대소문자만 다른 입력이 조회되지 않음
     */
    Optional<Member> findByEmail(String email);
}
