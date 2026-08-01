-- 과제 계약의 recordkey varchar 요구를 가변 길이 문자열로 반영.
-- 적용된 V1을 수정하지 않고 기존 데이터베이스를 보존하는 전진 보정.
ALTER TABLE health_connections
    MODIFY record_key VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT '공급자가 전달한 사용자 구분 키(recordkey). 최대 255자 문자열';
