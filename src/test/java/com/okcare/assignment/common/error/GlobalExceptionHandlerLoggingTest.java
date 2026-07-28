package com.okcare.assignment.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * 예외 메시지에 섞여 들어온 비밀번호가 로그로 새지 않는지 확인.
 *
 * <p>응답 본문만 검사하는 테스트로는 이 유출을 잡을 수 없다. 로그는 응답과 별도 경로이고,
 * 로거에 예외 객체를 넘기면 메시지와 stack trace가 통째로 기록된다.
 */
class GlobalExceptionHandlerLoggingTest {

    private static final String RAW_PASSWORD = "StrongPassword1";

    private final GlobalExceptionHandler handler =
            new GlobalExceptionHandler(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

    private ch.qos.logback.classic.Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        logger = context.getLogger(GlobalExceptionHandler.class);
        appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.TRACE);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("예외 메시지에 든 비밀번호를 로그에 남기지 않는다")
    void doesNotLogPasswordFromExceptionMessage() {
        Exception leaky = new IllegalStateException("insert 실패: password=" + RAW_PASSWORD);

        handler.handleUnexpected(leaky);

        assertThat(renderedLog()).doesNotContain(RAW_PASSWORD);
    }

    @Test
    @DisplayName("예외 cause에 든 비밀번호도 로그에 남기지 않는다")
    void doesNotLogPasswordFromCause() {
        Exception cause = new IllegalArgumentException("raw=" + RAW_PASSWORD);
        Exception wrapper = new IllegalStateException("저장 실패", cause);

        handler.handleUnexpected(wrapper);

        assertThat(renderedLog()).doesNotContain(RAW_PASSWORD);
    }

    @Test
    @DisplayName("원인 추적에 필요한 traceId와 예외 타입은 남긴다")
    void keepsTraceIdAndExceptionType() {
        handler.handleUnexpected(new IllegalStateException("boom"));

        assertThat(renderedLog())
                .contains("traceId=")
                .contains(IllegalStateException.class.getName());
    }

    /** 메시지와 함께 stack trace까지 펼쳐 검사. 예외 객체를 넘기면 여기서 값이 드러난다. */
    private String renderedLog() {
        StringBuilder rendered = new StringBuilder();
        for (ILoggingEvent event : appender.list) {
            rendered.append(event.getFormattedMessage());
            if (event.getThrowableProxy() != null) {
                StringWriter writer = new StringWriter();
                ((java.lang.Throwable)
                                ((ch.qos.logback.classic.spi.ThrowableProxy)
                                                event.getThrowableProxy())
                                        .getThrowable())
                        .printStackTrace(new PrintWriter(writer));
                rendered.append(writer);
            }
        }
        return rendered.toString();
    }
}
