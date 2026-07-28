package com.okcare.assignment;

import com.okcare.assignment.config.RequiredEnvironmentValidator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 고객 건강활동 데이터 수집 API의 진입점이다.
 *
 * <p>패키지 구성은 개발_계획.md §5.2의 기능 단위 구조를 따른다.
 */
@ConfigurationPropertiesScan
@SpringBootApplication
public class OkcareHealthApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(OkcareHealthApplication.class);
        // 개발_계획.md §9의 필수 설정 검사. 컨텍스트 생성 전에 멈추기 위해 리스너로 등록한다.
        application.addListeners(new RequiredEnvironmentValidator());
        application.run(args);
    }
}
