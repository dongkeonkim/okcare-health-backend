package com.okcare.assignment.health.domain;

/**
 * 한 저장 요청의 처리 결과.
 *
 * <p>{@code received}는 요청에 담긴 엔트리 수 그대로. 요청 안에 같은 식별자가 여러 번 들어오면 그
 * 식별자를 하나의 결과로만 세므로 나머지 셋의 합이 {@code received}보다 작을 수 있음.
 */
public record SaveResult(int received, int inserted, int updated, int duplicated) {}
