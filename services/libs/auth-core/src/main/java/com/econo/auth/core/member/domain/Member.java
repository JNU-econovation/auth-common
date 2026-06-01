package com.econo.auth.core.member.domain;

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
	private final LocalDateTime createdAt;

	private Member(
			Long id,
			String name,
			String loginId,
			String hashedPassword,
			Integer generation,
			MemberStatus status,
			LocalDateTime createdAt) {
		this.id = id;
		this.name = name;
		this.loginId = loginId;
		this.hashedPassword = hashedPassword;
		this.generation = generation;
		this.status = status;
		this.createdAt = createdAt;
	}

	/**
	 * 신규 회원 생성 팩토리 메서드
	 *
	 * @param name 이름
	 * @param loginId 로그인 아이디
	 * @param hashedPassword 해시된 비밀번호
	 * @param generation 기수
	 * @param status 활동 상태
	 * @return 새로 생성된 Member 인스턴스
	 */
	public static Member create(
			String name, String loginId, String hashedPassword, Integer generation, MemberStatus status) {
		return new Member(null, name, loginId, hashedPassword, generation, status, LocalDateTime.now());
	}

	/**
	 * JPA 엔티티 복원용 팩토리 메서드
	 *
	 * @param id 회원 ID
	 * @param name 이름
	 * @param loginId 로그인 아이디
	 * @param hashedPassword 해시된 비밀번호
	 * @param generation 기수
	 * @param status 활동 상태
	 * @param createdAt 생성 시각
	 * @return 복원된 Member 인스턴스
	 */
	public static Member restore(
			Long id,
			String name,
			String loginId,
			String hashedPassword,
			Integer generation,
			MemberStatus status,
			LocalDateTime createdAt) {
		return new Member(id, name, loginId, hashedPassword, generation, status, createdAt);
	}
}
