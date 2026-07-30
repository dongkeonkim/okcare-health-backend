package com.okcare.assignment.health.domain;

import java.time.Instant;
import java.util.List;

/**
 * 정규화를 마친 한 요청 전체.
 *
 * <p>{@code records}는 요청 순서를 유지하고 요청 내 중복을 접지 않음. 접는 시점을 저장 단계로
 * 미루는 이유는 {@code received}가 요청에 담긴 엔트리 수를 그대로 반환해야 하기 때문.
 */
public record NormalizedPayload(
        String recordKey,
        String sourceName,
        String productName,
        String vendorName,
        int sourceMode,
        Instant sourceLastUpdatedAt,
        List<NormalizedRecord> records) {

    public int received() {
        return records.size();
    }
}
