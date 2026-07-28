package com.okcare.assignment.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TimeConfig {

    /** {@code Instant.now()}를 직접 부르면 시각에 의존하는 동작을 테스트에서 고정할 수 없음. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
