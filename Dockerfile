# 개발_계획.md §10.1의 multi-stage build. 빌드는 JDK 17, 실행은 JRE 17을 사용한다.

FROM eclipse-temurin:17-jdk AS builder
WORKDIR /build

# 의존성 해석 결과를 별도 레이어로 캐시하기 위해 소스보다 빌드 파일을 먼저 복사한다.
COPY gradle gradle
COPY gradlew settings.gradle build.gradle ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies

COPY src src
# 이미지 빌드 단계에서는 테스트를 실행하지 않는다. 테스트는 Testcontainers가 필요하며
# 개발_계획.md §11의 검증 순서에 따라 호스트에서 ./gradlew build로 수행한다.
RUN ./gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:17-jre
# §10.1: 애플리케이션은 root가 아닌 전용 사용자로 실행한다.
RUN groupadd --system app && useradd --system --gid app --home-dir /app app
WORKDIR /app
COPY --from=builder /build/build/libs/*.jar app.jar
RUN chown -R app:app /app
USER app

# §6.2: 애플리케이션 기본 저장 타임존을 UTC로 고정한다.
ENV TZ=UTC
EXPOSE 8080
ENTRYPOINT ["java", "-Duser.timezone=UTC", "-jar", "/app/app.jar"]
