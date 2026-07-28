-- 개발_계획.md §6의 ERD와 §6.1의 제약조건, §6.2의 타입 규칙을 그대로 반영한다.
-- 컬럼 길이는 fixtures/health의 네 입력 파일에서 관찰된 값에 여유를 둬 정했다.
-- 과제_원문.md 제출물 2번이 ERD 코멘트를 요구하므로 모든 테이블과 컬럼에 한국어 COMMENT를 남긴다.

CREATE TABLE members
(
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '회원 식별자',
    name          VARCHAR(50)  NOT NULL COMMENT '회원 이름',
    nickname      VARCHAR(50)  NOT NULL COMMENT '회원 닉네임',
    email         VARCHAR(255) NOT NULL COMMENT '로그인 이메일. 앞뒤 공백 제거와 소문자 정규화를 거친 값을 저장한다',
    password_hash VARCHAR(100) NOT NULL COMMENT 'DelegatingPasswordEncoder 형식의 비밀번호 해시. 평문은 저장하지 않는다',
    created_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '생성 시각 (UTC)',
    updated_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '수정 시각 (UTC)',
    PRIMARY KEY (id),
    CONSTRAINT uk_members_email UNIQUE (email)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT '서비스 회원';

CREATE TABLE health_connections
(
    id           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '건강 데이터 연결 식별자',
    member_id    BIGINT      NOT NULL COMMENT '연결을 소유한 회원. 최초 저장 요청을 보낸 회원에게 귀속된다',
    record_key   CHAR(36)    NOT NULL COMMENT '공급자가 전달한 사용자 구분 키(recordkey). 입력은 UUID 36자다',
    source_name  VARCHAR(64) NOT NULL COMMENT '공급자 앱 이름. 입력의 data.source.name (예: SamsungHealth, Health Kit)',
    product_name VARCHAR(64) NOT NULL COMMENT '단말 제품명. 입력의 data.source.product.name (예: Android, iPhone)',
    vendor_name  VARCHAR(64) NOT NULL COMMENT '제조사명. 입력의 data.source.product.vender (오탈자는 공급자 원본 계약이다)',
    source_mode  INT         NOT NULL COMMENT '공급자가 전달한 수집 모드 코드. 입력의 data.source.mode (예: 9, 10)',
    created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '생성 시각 (UTC)',
    updated_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '수정 시각 (UTC)',
    PRIMARY KEY (id),
    CONSTRAINT uk_health_connections_record_key UNIQUE (record_key),
    CONSTRAINT fk_health_connections_member FOREIGN KEY (member_id) REFERENCES members (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT '회원과 recordkey의 소유권 연결. 공급자 메타데이터를 함께 보관한다';

-- record_key가 이미 UNIQUE이므로 소유권 확인만 위한 인덱스는 필요하지 않지만,
-- 개발_계획.md §6.1이 회원 기준 조회를 위해 복합 인덱스를 검토 대상으로 명시했다.
CREATE INDEX ix_health_connections_member_record_key
    ON health_connections (member_id, record_key);

CREATE TABLE health_activity_records
(
    id                     BIGINT         NOT NULL AUTO_INCREMENT COMMENT '건강활동 레코드 식별자',
    connection_id          BIGINT         NOT NULL COMMENT '소속 연결. 이 값이 recordkey와 공급자 연결을 대표하므로 두 값을 중복 저장하지 않는다',
    metric_type            VARCHAR(32)    NOT NULL COMMENT '지표 종류. 입력 최상위의 type (예: steps)',
    period_start_utc       DATETIME(6)    NOT NULL COMMENT '측정 구간 시작 시각 (UTC 정규화)',
    period_end_utc         DATETIME(6)    NOT NULL COMMENT '측정 구간 종료 시각 (UTC 정규화). 시작과 같을 수 있다',
    activity_date          DATE           NOT NULL COMMENT '집계 기준 날짜. 시작 시각을 app.business-zone(Asia/Seoul)으로 변환해 정한다',
    steps                  DECIMAL(24, 12) NOT NULL COMMENT '걸음수 원본값. 공급자가 소수를 보내므로 정밀도를 보존한다',
    calories               DECIMAL(24, 12) NOT NULL COMMENT '소모 칼로리 원본값. 0은 유효한 값이다',
    distance               DECIMAL(24, 12) NOT NULL COMMENT '이동거리 원본값',
    calories_unit          VARCHAR(16)    NOT NULL COMMENT '칼로리 단위. kcal만 허용한다',
    distance_unit          VARCHAR(16)    NOT NULL COMMENT '거리 단위. km만 허용한다',
    source_last_updated_at DATETIME(6)    NOT NULL COMMENT '공급자가 알린 최종 갱신 시각 (UTC 정규화). 입력의 lastUpdate',
    payload_hash           CHAR(64)       NOT NULL COMMENT '측정값 변경 감지를 위한 SHA-256 해시(hex). 원본 payload는 저장하지 않는다',
    created_at             DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '생성 시각 (UTC)',
    updated_at             DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '수정 시각 (UTC)',
    PRIMARY KEY (id),
    -- 기능_명세.md §5.4의 동일 레코드 판단 기준이다. 멱등성의 최종 보장을 이 제약조건에 맡긴다.
    CONSTRAINT uk_health_activity_records_identity UNIQUE (connection_id, metric_type, period_start_utc, period_end_utc),
    CONSTRAINT fk_health_activity_records_connection FOREIGN KEY (connection_id) REFERENCES health_connections (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT '공급자별로 정규화한 건강활동 측정 레코드';

-- Daily/Monthly 집계 조회의 주 경로다.
CREATE INDEX ix_health_activity_records_connection_date
    ON health_activity_records (connection_id, activity_date);

-- 개발_계획.md §6.1의 검토 대상 인덱스. 식별자 UNIQUE는 metric_type이 두 번째 컬럼이라
-- 기간만으로 범위 조회할 때 사용할 수 없어 별도로 둔다.
CREATE INDEX ix_health_activity_records_connection_period
    ON health_activity_records (connection_id, period_start_utc, period_end_utc);
