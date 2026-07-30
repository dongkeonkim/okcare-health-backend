package com.okcare.assignment.health.api;

import com.okcare.assignment.config.AppProperties;
import com.okcare.assignment.health.application.HealthAggregationService;
import com.okcare.assignment.health.application.HealthDataService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.YearMonth;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 보호된 경로. {@code SecurityConfig}의 {@code anyRequest().authenticated()}가 공개 목록에 없는
 * 이 경로를 자동으로 막으므로 설정을 따로 고치지 않음.
 */
@RestController
@RequestMapping("/api/v1/health-data")
public class HealthDataController {

    private final HealthDataService healthDataService;
    private final HealthAggregationService healthAggregationService;
    private final AppProperties appProperties;

    public HealthDataController(
            HealthDataService healthDataService,
            HealthAggregationService healthAggregationService,
            AppProperties appProperties) {
        this.healthDataService = healthDataService;
        this.healthAggregationService = healthAggregationService;
        this.appProperties = appProperties;
    }

    @PostMapping
    public HealthDataSaveResponse save(
            @AuthenticationPrincipal Long memberId,

            @Valid
            @RequestBody
            HealthDataRequest request
    ) {
        return HealthDataSaveResponse.from(
                request.recordkey(), healthDataService.save(memberId, request));
    }

    @GetMapping("/daily")
    public DailyAggregationResponse daily(
            @AuthenticationPrincipal Long memberId,
            @RequestParam String recordKey,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to
    ) {
        return DailyAggregationResponse.of(
                recordKey,
                appProperties.businessZone(),
                healthAggregationService.daily(memberId, recordKey, from, to));
    }

    @GetMapping("/monthly")
    public MonthlyAggregationResponse monthly(
            @AuthenticationPrincipal Long memberId,
            @RequestParam String recordKey,
            @RequestParam YearMonth from,
            @RequestParam YearMonth to
    ) {
        return MonthlyAggregationResponse.of(
                recordKey,
                appProperties.businessZone(),
                healthAggregationService.monthly(memberId, recordKey, from, to));
    }
}
