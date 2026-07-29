package com.okcare.assignment;

/**
 * 테스트 컨텍스트가 공유하는 설정값.
 *
 * <p>{@code JWT_SECRET}은 서명 경로를 쓰지 않는 테스트에도 필요함. 안전 기준을 통과하지 못하면
 * 컨텍스트가 뜨지 않아 무관한 테스트가 함께 실패함.
 *
 * <p>애노테이션 속성에 쓰이므로 컴파일 시점 상수여야 함. 값을 조립하거나 다른 상수를 참조하면
 * {@code @SpringBootTest(properties = ...)}에서 쓸 수 없음.
 */
public final class TestSecrets {

    /** Base64 디코딩 후 32바이트. 안전 기준을 충족하는 최소 길이. */
    public static final String JWT_SECRET = "b2tjYXJlLWludGVncmF0aW9uLXRlc3Qtc2VjcmV0ISE=";

    private TestSecrets() {}
}
