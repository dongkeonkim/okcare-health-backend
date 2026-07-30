package com.okcare.assignment.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 hex 변환. 리프레시 토큰 저장과 측정값 변경 감지가 같은 구현을 쓰도록 한곳에 모음. */
public final class Sha256 {

    private Sha256() {}

    public static String hex(String value) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256은 모든 JDK가 제공하도록 규격이 요구하므로 도달할 수 없음.
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }
}
