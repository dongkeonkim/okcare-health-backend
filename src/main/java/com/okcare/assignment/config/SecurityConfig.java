package com.okcare.assignment.config;

import com.okcare.assignment.common.security.JwtAuthenticationFilter;
import com.okcare.assignment.common.security.TokenAuthenticationEntryPoint;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    /**
     * 상태를 갖지 않는 Bearer 토큰 인증 체인.
     *
     * <p>{@code /actuator/health}를 반드시 열어야 함. Compose healthcheck가 이 엔드포인트 응답에서
     * {@code "status":"UP"}을 찾으므로 막으면 app을 포함한 세 컨테이너가 모두 unhealthy가 되고
     * {@code docker compose up}이 끝나지 않음. 경로 문자열을 여기서 다시 쓰지 않고 actuator 설정의
     * base path에서 만들어, 설정과 매처가 어긋나는 경우를 없앰. {@code compose.yaml}의
     * healthcheck는 여전히 경로를 하드코딩하므로 base path를 바꾸려면 그쪽도 함께 고쳐야 함.
     *
     * <p>가입·로그인·재발급은 자격 증명을 본문으로 받아 인증 이전에 동작해야 하므로 공개.
     * 로그아웃만 액세스 토큰을 요구.
     *
     * <p>공개 경로를 메서드로 좁히지 않음. {@code POST}만 허용하면 잘못된 메서드 요청이 MVC에
     * 닿지 못해 405 대신 401이 나가고, 공개 엔드포인트의 응답이 실제 원인과 어긋남. 이 경로들에는
     * 다른 메서드 핸들러가 없으므로 열어 둬도 늘어나는 표면이 없음.
     *
     * <p>세션을 만들지 않고 CSRF를 끄는 이유는 인증 상태를 쿠키가 아니라 요청마다 오는 토큰이
     * 나르기 때문. 세션이 있으면 로그아웃 후에도 서버 상태가 남아 계약과 어긋남.
     *
     * <p>{@code formLogin}과 {@code httpBasic}을 활성화하지 않음. 스타터 기본값이 켜면 브라우저
     * 인증 창이 뜨고 우리 오류 형식이 아닌 응답이 나감.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            TokenAuthenticationEntryPoint authenticationEntryPoint,
            WebEndpointProperties webEndpointProperties)
            throws Exception {

        String healthPath = webEndpointProperties.getBasePath() + "/health";

        return http.csrf(csrf -> csrf.disable())
                // 기본 logout configurer를 끔. 켜져 있으면 LogoutFilter가 /logout을 가로채
                // 기능 명세에 없는 공개 엔드포인트가 생김. 그 필터는 인가 필터보다 앞이라
                // anyRequest().authenticated()로 막히지도 않고, CSRF를 끈 상태에서는 네 가지
                // 메서드를 모두 받아 /login?logout으로 302를 반환함.
                .logout(logout -> logout.disable())
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        requests ->
                                requests.requestMatchers(
                                                healthPath,
                                                "/api/v1/auth/signup",
                                                "/api/v1/auth/login",
                                                "/api/v1/auth/refresh")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .exceptionHandling(
                        handling -> handling.authenticationEntryPoint(authenticationEntryPoint))
                .addFilterBefore(
                        jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
