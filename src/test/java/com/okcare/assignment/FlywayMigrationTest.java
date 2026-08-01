package com.okcare.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.okcare.assignment.config.AppProperties;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 빈 MySQL에 Flyway migration 전체를 적용하는 검증.
 *
 * <p>스키마가 실제 MySQL 방언에 적용된 결과만 확인.
 *
 * <p>MySQL 접속 정보는 {@link ServiceConnection}이 컨테이너에서 직접 공급하므로 {@code DB_*}는
 * 불필요. Redis는 연결이 지연 생성되어 컨테이너 없이도 되지만, placeholder를 해석해야 컨텍스트가
 * 뜨므로 운영과 같은 이름으로 값만 채움.
 */
@Testcontainers
@SpringBootTest(
        properties = {
            "REDIS_HOST=localhost",
            "REDIS_PORT=6379",
            "JWT_SECRET=" + TestSecrets.JWT_SECRET
        })
class FlywayMigrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @Autowired private JdbcTemplate jdbc;

    @Autowired private AppProperties appProperties;

    @Test
    @DisplayName("Flyway가 빈 MySQL에 모든 migration을 적용한다")
    void appliesAllMigrations() {
        List<String> applied =
                jdbc.queryForList(
                        """
                        SELECT version FROM flyway_schema_history
                        WHERE success = 1
                        ORDER BY installed_rank
                        """,
                        String.class);

        assertThat(applied).containsExactly("1", "2", "3");
    }

    @Test
    @DisplayName("ERD의 세 테이블을 생성한다")
    void createsAllTables() {
        assertThat(tableNames())
                .contains("members", "health_connections", "health_activity_records");
    }

    @Test
    @DisplayName("UNIQUE 제약조건을 생성한다")
    void createsUniqueConstraints() {
        // 멱등성의 최종 보장이 이 제약조건에 달려 있으므로 컬럼 구성까지 확인.
        assertThat(indexColumns("health_activity_records", "uk_health_activity_records_identity"))
                .containsExactly(
                        "connection_id", "metric_type", "period_start_utc", "period_end_utc");
        assertThat(indexColumns("members", "uk_members_email")).containsExactly("email");
        assertThat(indexColumns("health_connections", "uk_health_connections_record_key"))
                .containsExactly("record_key");
    }

    @Test
    @DisplayName("외래 키가 고아 행 삽입을 거부한다")
    void rejectsOrphanRows() {
        // 테이블·UNIQUE·인덱스 단언만으로 FK 누락 확인 불가.
        // 실제 삽입 거부까지 검증.
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        """
                                        insert into health_connections
                                            (member_id, record_key, source_name, product_name,
                                             vendor_name, source_mode)
                                        values (999999, ?, 'SamsungHealth', 'Android', 'Samsung', 9)
                                        """,
                                        "00000000-0000-0000-0000-000000000001"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        """
                                        insert into health_activity_records
                                            (connection_id, metric_type, period_start_utc,
                                             period_end_utc, activity_date, steps, calories,
                                             distance, calories_unit, distance_unit,
                                             source_last_updated_at, payload_hash)
                                        values (999999, 'steps', '2024-11-15 00:00:00',
                                                '2024-11-15 00:10:00', '2024-11-15', 1, 1, 1,
                                                'kcal', 'km', '2024-11-15 00:00:00', ?)
                                        """,
                                        "0".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("이메일 컬럼에 악센트 구분 콜레이션을 적용한다")
    void usesAccentSensitiveEmailCollation() {
        // 문자열 상수에 목표 콜레이션을 붙인 비교만으로는 V2 누락 검출 불가. 실제 컬럼
        // 메타데이터 확인으로 migration 누락과 다른 컬럼 변경 검출.
        String collation =
                jdbc.queryForObject(
                        """
                        SELECT collation_name FROM information_schema.columns
                        WHERE table_schema = DATABASE()
                          AND table_name = 'members'
                          AND column_name = 'email'
                        """,
                        String.class);

        assertThat(collation).isEqualTo("utf8mb4_0900_as_cs");
    }

    @Test
    @DisplayName("recordkey 컬럼에 대소문자 구분 콜레이션을 적용한다")
    void usesCaseSensitiveRecordKeyCollation() {
        String collation =
                jdbc.queryForObject(
                        """
                        SELECT collation_name FROM information_schema.columns
                        WHERE table_schema = DATABASE()
                          AND table_name = 'health_connections'
                          AND column_name = 'record_key'
                        """,
                        String.class);

        assertThat(collation).isEqualTo("utf8mb4_0900_as_cs");
    }

    @Test
    @DisplayName("소유권과 집계 조회 인덱스를 생성한다")
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
    @DisplayName("측정값과 시각에 요구된 정밀도를 사용한다")
    void usesRequiredColumnTypes() {
        // 측정값 정밀도와 시각 정밀도가 어긋나면 회귀 기대값을 맞출 수 없음.
        assertThat(columnType("health_activity_records", "steps")).isEqualTo("decimal(24,12)");
        assertThat(columnType("health_activity_records", "calories")).isEqualTo("decimal(24,12)");
        assertThat(columnType("health_activity_records", "distance")).isEqualTo("decimal(24,12)");
        assertThat(columnType("health_activity_records", "period_start_utc"))
                .isEqualTo("datetime(6)");
        assertThat(columnType("health_activity_records", "activity_date")).isEqualTo("date");
        assertThat(columnType("health_connections", "record_key")).isEqualTo("varchar(255)");
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
