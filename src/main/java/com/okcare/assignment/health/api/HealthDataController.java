package com.okcare.assignment.health.api;

import com.okcare.assignment.health.application.HealthDataService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 보호된 경로. {@code SecurityConfig}의 {@code anyRequest().authenticated()}가 공개 목록에 없는
 * 이 경로를 자동으로 막으므로 설정을 따로 고치지 않음.
 */
@RestController
@RequestMapping("/api/v1/health-data")
public class HealthDataController {

    private final HealthDataService healthDataService;

    public HealthDataController(HealthDataService healthDataService) {
        this.healthDataService = healthDataService;
    }

    @PostMapping
    public HealthDataSaveResponse save(
            @AuthenticationPrincipal
            Long memberId,

            @Valid
            @RequestBody
            HealthDataRequest request) {
        return HealthDataSaveResponse.from(
                request.recordkey(), healthDataService.save(memberId, request));
    }
}
