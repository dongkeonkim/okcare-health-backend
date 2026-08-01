package com.okcare.assignment.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.okcare.assignment.IntegrationSupport;
import com.okcare.assignment.health.domain.HealthConnection;
import com.okcare.assignment.health.domain.NormalizedPayload;
import com.okcare.assignment.health.infrastructure.HealthConnectionRepository;
import com.okcare.assignment.member.application.MemberSignupService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

class PersistenceConstraintLoggingIntegrationTest extends IntegrationSupport {

    private static final String HIBERNATE_SQL_EXCEPTION_LOGGER =
            "org.hibernate.engine.jdbc.spi.SqlExceptionHelper";

    @Autowired private MemberSignupService memberSignupService;

    @Autowired private HealthConnectionRepository healthConnectionRepository;

    @Test
    @DisplayName("이메일 UNIQUE 위반이 이메일을 Hibernate 로그에 남기지 않는다")
    void doesNotLogEmailFromUniqueViolation() {
        String email = "constraint-log-email@example.com";
        memberSignupService.signup("홍길동", "길동", email, PASSWORD);

        try (HibernateSqlLogCapture logs = HibernateSqlLogCapture.start()) {
            assertThatThrownBy(
                            () ->
                                    memberSignupService.signup(
                                            "홍길동", "길동", email, PASSWORD))
                    .isInstanceOf(BusinessException.class);

            assertThat(logs.rendered()).doesNotContain(email);
        }
    }

    @Test
    @DisplayName("recordkey UNIQUE 위반이 recordkey를 Hibernate 로그에 남기지 않는다")
    void doesNotLogRecordkeyFromUniqueViolation() throws Exception {
        long ownerId = signup("constraint-log-owner@example.com");
        long contenderId = signup("constraint-log-contender@example.com");
        String recordkey = "constraint-log-recordkey";
        NormalizedPayload payload =
                new NormalizedPayload(
                        recordkey,
                        "SamsungHealth",
                        "Android",
                        "Samsung",
                        9,
                        Instant.parse("2024-11-15T01:00:00Z"),
                        List.of());

        healthConnectionRepository.saveAndFlush(HealthConnection.create(ownerId, payload));

        try (HibernateSqlLogCapture logs = HibernateSqlLogCapture.start()) {
            assertThatThrownBy(
                            () ->
                                    healthConnectionRepository.saveAndFlush(
                                            HealthConnection.create(contenderId, payload)))
                    .isInstanceOf(DataIntegrityViolationException.class);

            assertThat(logs.rendered()).doesNotContain(recordkey);
        }
    }

    private static final class HibernateSqlLogCapture implements AutoCloseable {

        private final Logger logger;
        private final ListAppender<ILoggingEvent> appender;

        private HibernateSqlLogCapture(Logger logger, ListAppender<ILoggingEvent> appender) {
            this.logger = logger;
            this.appender = appender;
        }

        static HibernateSqlLogCapture start() {
            Logger logger = (Logger) LoggerFactory.getLogger(HIBERNATE_SQL_EXCEPTION_LOGGER);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            return new HibernateSqlLogCapture(logger, appender);
        }

        String rendered() {
            return appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
