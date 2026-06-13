package com.econo.auth.member.application.repository;

/** 비밀번호 해싱 아웃바운드 포트 */
public interface PasswordHasher {

	/**
	 * 비밀번호 해싱
	 *
	 * @param rawPassword 평문 비밀번호
	 * @return 해시된 비밀번호
	 */
	String hash(String rawPassword);

	/**
	 * 비밀번호 일치 여부 확인
	 *
	 * @param rawPassword 평문 비밀번호
	 * @param hashedPassword 해시된 비밀번호
	 * @return 일치하면 true
	 */
	boolean matches(String rawPassword, String hashedPassword);
}
