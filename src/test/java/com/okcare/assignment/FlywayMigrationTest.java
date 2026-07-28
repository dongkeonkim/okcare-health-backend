package com.okcare.assignment;

import static org.assertj.core.api.Assertions.assertThat;

import com.okcare.assignment.config.AppProperties;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 빈 MySQL에 Flyway migration 전체를 적용하는 검증이다.
 *
 * <p>개발_계획.md §6.3이 이 테스트를 요구한다. 스키마 정의는 §6·§6.1·§6.2를 단일 기준으로 하며,
 * 여기서는 실제 MySQL 방언에 적용된 결과만 확인한다.
 *
 * <p>Redis 연결은 지연 생성되므로 이 테스트에서는 컨테이너를 띄우지 않고 값만 채운다. Redis
 * 연결은 경계 3의 Docker Compose healthcheck에서 확인한다.
 *
 * <p>MySQL 접속 정보는 {@link ServiceConnection}이 컨테이너에서 직접 공급하므로 {@code DB_*}는
 * 필요하지 않다. Redis 설정은 application.yml의 placeholder를 해석해야 컨텍스트가 뜨므로 운영과
 * 같은 이름으로 채운다.
 */
@Testcontainers
@SpringBootTest(properties = {"REDIS_HOST=localhost", "REDIS_PORT=6379"})
class FlywayMigrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @Autowired private JdbcTemplate jdbc;

    @Autowired private AppProperties appProperties;

    @Test
    @DisplayName("Flyway가 빈 MySQL에 V1 migration을 적용한다")
    void appliesAllMigrations() {
        List<String> applied =
                jdbc.queryForList(
                        "SELECT version FROM flyway_schema_history WHERE success = 1", String.class);

        assertThat(applied).contains("1");
    }

    @Test
    @DisplayName("ERD의 세 테이블을 생성한다")
    void createsAllTables() {
        assertThat(tableNames())
                .contains("members", "health_connections", "health_activity_records");
    }

    @Test
    @DisplayName("§6.1의 UNIQUE 제약조건을 생성한다")
    void createsUniqueConstraints() {
        // 멱등성의 최종 보장이 이 제약조건에 달려 있으므로 컬럼 구성까지 확인한다.
        assertThat(indexColumns("health_activity_records", "uk_health_activity_records_identity"))
                .containsExactly(
                        "connection_id", "metric_type", "period_start_utc", "period_end_utc");
        assertThat(indexColumns("members", "uk_members_email")).containsExactly("email");
        assertThat(indexColumns("health_connections", "uk_health_connections_record_key"))
                .containsExactly("record_key");
    }

    @Test
    @DisplayName("§6.1의 조회 인덱스를 생성한다")
    void createsLookupIndexes() {
        assertThat(indexColumns("health_connections", "ix_health_connections_member_record_key"))
                .containsExactly("member_id", "record_key");
        assertThat(
                        indexColumns(
                                "health_activity_records",
                                "ix_health_activity_records_connection_date"))
                .containsExactly("connection_id", "activity_date");
        assertThat(
                        indexColumns(
                                "health_activity_records",
                                "ix_health_activity_records_connection_period"))
                .containsExactly("connection_id", "period_start_utc", "period_end_utc");
    }

    @Test
    @DisplayName("§6.2의 숫자와 시간 타입을 사용한다")
    void usesRequiredColumnTypes() {
        // 측정값 정밀도와 시각 정밀도가 어긋나면 회귀 기대값을 맞출 수 없다.
        assertThat(columnType("health_activity_records", "steps")).isEqualTo("decimal(24,12)");
        assertThat(columnType("health_activity_records", "calories")).isEqualTo("decimal(24,12)");
        assertThat(columnType("health_activity_records", "distance")).isEqualTo("decimal(24,12)");
        assertThat(columnType("health_activity_records", "period_start_utc"))
                .isEqualTo("datetime(6)");
        assertThat(columnType("health_activity_records", "activity_date")).isEqualTo("date");
        assertThat(columnType("health_connections", "record_key")).isEqualTo("char(36)");
        assertThat(columnType("health_activity_records", "payload_hash")).isEqualTo("char(64)");
    }

    @Test
    @DisplayName("집계 기준 타임존 설정이 검증된 값으로 바인딩된다")
    void bindsBusinessZone() {
        assertThat(appProperties.businessZone()).isEqualTo(ZoneId.of("Asia/Seoul"));
    }

    private List<String> tableNames() {
        return jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()",
                String.class);
    }

    private List<String> indexColumns(String table, String index) {
        return jdbc.queryForList(
                """
                SELECT column_name FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?
                ORDER BY seq_in_index
                """,
                String.class,
                table,
                index);
    }

    private String columnType(String table, String column) {
        return jdbc.queryForObject(
                """
                SELECT column_type FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
                """,
                String.class,
                table,
                column);
    }
}
