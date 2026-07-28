package com.okcare.assignment.member.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Locale;
import java.util.Objects;

/**
 * 서비스 회원.
 *
 * <p>{@code created_at}과 {@code updated_at}은 DDL 기본값이 채우므로 매핑하지 않음.
 */
@Entity
@Table(name = "members")
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false, length = 50)
    private String nickname;

    @Column(nullable = false, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    protected Member() {}

    private Member(String name, String nickname, String email, String passwordHash) {
        this.name = name;
        this.nickname = nickname;
        this.email = email;
        this.passwordHash = passwordHash;
    }

    /**
     * 회원 생성.
     *
     * <p>정규화를 호출부가 아니라 여기서 수행. 이메일 중복은 {@code uk_members_email}이 저장된
     * 값으로 판정하므로, 한 경로라도 정규화를 빠뜨리면 대소문자만 다른 중복 계정이 생김.
     *
     * @param passwordHash 이미 해시된 값. 평문을 넘기지 않음.
     */
    public static Member create(String name, String nickname, String email, String passwordHash) {
        return new Member(
                Objects.requireNonNull(name, "name"),
                Objects.requireNonNull(nickname, "nickname"),
                normalizeEmail(email),
                Objects.requireNonNull(passwordHash, "passwordHash"));
    }

    /**
     * 앞뒤 공백 제거 후 소문자 변환.
     *
     * <p>{@link Locale#ROOT}를 지정하지 않으면 터키어 로캘에서 {@code I}가 점 없는 소문자로 바뀌어
     * 같은 이메일이 서버 로캘에 따라 다른 값으로 저장됨.
     */
    public static String normalizeEmail(String email) {
        return Objects.requireNonNull(email, "email").trim().toLowerCase(Locale.ROOT);
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getNickname() {
        return nickname;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }
}
