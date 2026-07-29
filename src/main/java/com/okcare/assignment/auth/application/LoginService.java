package com.okcare.assignment.auth.application;

import com.okcare.assignment.auth.domain.IssuedTokens;
import com.okcare.assignment.auth.infrastructure.JwtTokenProvider;
import com.okcare.assignment.auth.infrastructure.RefreshTokenStore;
import com.okcare.assignment.common.error.BusinessException;
import com.okcare.assignment.common.error.ErrorCode;
import com.okcare.assignment.member.domain.Member;
import com.okcare.assignment.member.infrastructure.MemberRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class LoginService {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenStore refreshTokenStore;

    public LoginService(
            MemberRepository memberRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider,
            RefreshTokenStore refreshTokenStore) {
        this.memberRepository = memberRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.refreshTokenStore = refreshTokenStore;
    }

    /**
     * 자격 증명 확인 후 토큰 발급.
     *
     * <p>{@code @Transactional}을 붙이지 않음. DB 작업이 단건 조회뿐이고, 트랜잭션을 열면 토큰
     * 저장의 Redis 왕복이 그 안에 들어감.
     *
     * <p>기존에 발급한 토큰을 폐기하지 않음. 저장 키가 발급마다 다른 {@code tokenId}를 포함하므로
     * 여러 기기의 로그인이 공존.
     *
     * @param rawPassword 평문 비밀번호. 비교 후 보관하지 않음.
     * @throws BusinessException 회원이 없거나 비밀번호가 일치하지 않을 때. 두 경우를 같은 코드로
     *     처리해 이메일 등록 여부를 노출하지 않음
     */
    public IssuedTokens login(String email, String rawPassword) {
        Member member = findMember(email);

        if (!passwordEncoder.matches(rawPassword, member.getPasswordHash())) {
            throw new BusinessException(ErrorCode.AUTH_CREDENTIALS_INVALID);
        }

        IssuedTokens tokens = jwtTokenProvider.issue(member.getId());
        refreshTokenStore.save(member.getId(), tokens);

        return tokens;
    }

    /** 저장된 이메일이 정규화된 값이므로 조회 조건도 같은 규칙을 거쳐야 함. */
    private Member findMember(String email) {
        return memberRepository
                .findByEmail(Member.normalizeEmail(email))
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_CREDENTIALS_INVALID));
    }
}
