package com.okcare.assignment;

import com.okcare.assignment.config.RequiredEnvironmentValidator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@ConfigurationPropertiesScan
@SpringBootApplication
public class OkcareHealthApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(OkcareHealthApplication.class);
        // 컨텍스트 생성 전에 멈춰야 하므로 빈이 아니라 리스너로 등록.
        application.addListeners(new RequiredEnvironmentValidator());
        application.run(args);
    }
}
