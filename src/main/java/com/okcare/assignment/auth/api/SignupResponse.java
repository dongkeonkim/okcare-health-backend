package com.okcare.assignment.auth.api;

import com.okcare.assignment.member.domain.Member;

/** 엔티티를 그대로 직렬화하지 않음. 비밀번호 해시가 응답에 실려 나가면 안 됨. */
public record SignupResponse(Long id, String name, String nickname, String email) {

    public static SignupResponse from(Member member) {
        return new SignupResponse(
                member.getId(), member.getName(), member.getNickname(), member.getEmail());
    }
}
