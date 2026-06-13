package com.econo.auth.member.application.usecase;

import com.econo.auth.member.application.domain.MemberStatus;

/** 회원 가입 인바운드 포트 */
public interface SignupUseCase {

	/**
	 * 회원 가입
	 *
	 * @param command 가입 커맨드
	 */
	void signup(SignupCommand command);

	/**
	 * 회원 가입 커맨드
	 *
	 * @param name 이름
	 * @param loginId 로그인 아이디
	 * @param password 평문 비밀번호
	 * @param generation 기수
	 * @param status 활동 상태
	 */
	record SignupCommand(
			String name, String loginId, String password, Integer generation, MemberStatus status) {}
}
