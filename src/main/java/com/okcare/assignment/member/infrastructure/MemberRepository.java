package com.okcare.assignment.member.infrastructure;

import com.okcare.assignment.member.domain.Member;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 회원 영속화.
 *
 * <p>이메일 존재 여부 조회 메서드를 두지 않음. 동시 요청에서는 양쪽 모두 사전 조회를 통과해
 * 중복이 생기므로, 판정은 {@code uk_members_email} 제약에 위임.
 */
public interface MemberRepository extends JpaRepository<Member, Long> {}
