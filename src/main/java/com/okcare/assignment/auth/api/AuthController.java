package com.okcare.assignment.auth.api;

import com.okcare.assignment.auth.application.LoginService;
import com.okcare.assignment.auth.application.TokenRefreshService;
import com.okcare.assignment.member.application.MemberSignupService;
import com.okcare.assignment.member.domain.Member;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final MemberSignupService memberSignupService;
    private final LoginService loginService;
    private final TokenRefreshService tokenRefreshService;

    public AuthController(
            MemberSignupService memberSignupService,
            LoginService loginService,
            TokenRefreshService tokenRefreshService) {
        this.memberSignupService = memberSignupService;
        this.loginService = loginService;
        this.tokenRefreshService = tokenRefreshService;
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public SignupResponse signup(
            @Valid
            @RequestBody
            SignupRequest request) {
        Member member =
                memberSignupService.signup(
                        request.name(), request.nickname(), request.email(), request.password());

        return SignupResponse.from(member);
    }

    @PostMapping("/login")
    public TokenResponse login(
            @Valid
            @RequestBody
            LoginRequest request) {
        return TokenResponse.from(loginService.login(request.email(), request.password()));
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(
            @Valid
            @RequestBody
            RefreshRequest request) {
        return TokenResponse.from(tokenRefreshService.refresh(request.refreshToken()));
    }
}
