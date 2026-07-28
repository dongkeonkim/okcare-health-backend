# multi-stage build. 빌드는 JDK, 실행은 JRE 이미지를 써서 최종 이미지에 컴파일러를 남기지 않음.

FROM eclipse-temurin:17-jdk AS builder
WORKDIR /build

# 의존성 해석 결과를 별도 레이어로 캐시하기 위해 소스보다 빌드 파일을 먼저 복사.
COPY gradle gradle
COPY gradlew settings.gradle build.gradle ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies

COPY src src
# 테스트는 Testcontainers가 Docker 데몬을 필요로 해 이미지 빌드 안에서 실행 불가.
# 호스트에서 ./gradlew build로 수행.
RUN ./gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:17-jre
# 컨테이너 탈출 시 피해를 줄이기 위해 root가 아닌 전용 사용자로 실행.
RUN groupadd --system app && useradd --system --gid app --home-dir /app app
WORKDIR /app
COPY --from=builder /build/build/libs/*.jar app.jar
RUN chown -R app:app /app
USER app

# 기본 타임존을 고정하지 않으면 호스트 설정에 따라 저장 시각이 달라짐.
ENV TZ=UTC
EXPOSE 8080
ENTRYPOINT ["java", "-Duser.timezone=UTC", "-jar", "/app/app.jar"]
