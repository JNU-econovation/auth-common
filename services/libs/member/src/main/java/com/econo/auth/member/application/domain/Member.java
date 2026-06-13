package com.econo.auth.member.application.domain;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Getter;

/** 회원 Aggregate Root — 불변 도메인 객체 */
@Getter
public class Member implements Serializable {

	@Serial private static final long serialVersionUID = 1L;

	private final Long id;
	private final String name;
	private final String loginId;
	private final String hashedPassword;
	private final Integer generation;
	private final MemberStatus status;
	private final String role;
	private final LocalDateTime createdAt;

	private Member(
			Long id,
			String name,
			String loginId,
			String hashedPassword,
			Integer generation,
			MemberStatus status,
			String role,
			LocalDateTime createdAt) {
		this.id = id;
		this.name = name;
		this.loginId = loginId;
		this.hashedPassword = hashedPassword;
		this.generation = generation;
		this.status = status;
		this.role = role != null ? role : "USER";
		this.createdAt = createdAt;
	}

	public static Member create(
			String name, String loginId, String hashedPassword, Integer generation, MemberStatus status) {
		return new Member(
				null, name, loginId, hashedPassword, generation, status, "USER", LocalDateTime.now());
	}

	public static Member restore(
			Long id,
			String name,
			String loginId,
			String hashedPassword,
			Integer generation,
			MemberStatus status,
			String role,
			LocalDateTime createdAt) {
		return new Member(id, name, loginId, hashedPassword, generation, status, role, createdAt);
	}

	public boolean isAdmin() {
		return "ADMIN".equals(role) || "SUPER_ADMIN".equals(role);
	}

	/** 역할이 변경된 새 Member 인스턴스 반환 (불변 객체 패턴) */
	public Member withRole(String newRole) {
		return new Member(id, name, loginId, hashedPassword, generation, status, newRole, createdAt);
	}
}
