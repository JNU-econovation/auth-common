package com.econo.auth.core.member.application.port.in;

/** 로그인 인바운드 포트 */
public interface LoginUseCase {

	/**
	 * 로그인
	 *
	 * @param command 로그인 커맨드
	 * @return 로그인 결과 (JWT 토큰 포함)
	 */
	LoginResult login(LoginCommand command);

	/**
	 * 로그인 커맨드
	 *
	 * @param loginId 로그인 아이디
	 * @param password 평문 비밀번호
	 */
	record LoginCommand(String loginId, String password) {}

	/**
	 * 로그인 결과
	 *
	 * @param jwtToken 발급된 JWT 토큰
	 */
	record LoginResult(String jwtToken) {}
}
