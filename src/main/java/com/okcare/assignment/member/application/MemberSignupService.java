package com.okcare.assignment.member.application;

import com.okcare.assignment.common.error.BusinessException;
import com.okcare.assignment.common.error.ErrorCode;
import com.okcare.assignment.member.domain.Member;
import com.okcare.assignment.member.infrastructure.MemberRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemberSignupService {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;

    public MemberSignupService(MemberRepository memberRepository, PasswordEncoder passwordEncoder) {
        this.memberRepository = memberRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 회원 등록.
     *
     * @param rawPassword 평문 비밀번호. 해시한 뒤에는 보관하지 않음.
     * @throws BusinessException 정규화한 이메일이 이미 등록되어 있을 때
     */
    @Transactional
    public Member signup(String name, String nickname, String email, String rawPassword) {
        Member member = Member.create(name, nickname, email, passwordEncoder.encode(rawPassword));

        try {
            // 명시적 flush로 제약 위반을 이 catch 경계 안에서 관찰. flush 시점이 ID 전략에
            // 좌우되지 않으므로, 나중에 채번 방식이 바뀌어도 409가 500으로 새지 않는다.
            return memberRepository.saveAndFlush(member);
        } catch (DataIntegrityViolationException e) {
            // members의 UNIQUE 제약은 uk_members_email 하나뿐이라 원인이 특정됨.
            throw new BusinessException(ErrorCode.MEMBER_EMAIL_DUPLICATED);
        }
    }
}
