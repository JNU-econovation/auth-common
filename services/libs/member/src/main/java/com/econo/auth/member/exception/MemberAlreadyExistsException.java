package com.econo.auth.member.exception;

/** loginId 중복 예외 — HTTP 매핑은 GlobalExceptionHandler가 담당 */
public class MemberAlreadyExistsException extends RuntimeException {

	public static final String ERROR_CODE = "MEMBER_ALREADY_EXISTS";

	private MemberAlreadyExistsException(String message) {
		super(message);
	}

	/**
	 * loginId 중복 예외 생성
	 *
	 * @param loginId 중복된 loginId
	 * @return MemberAlreadyExistsException 인스턴스
	 */
	public static MemberAlreadyExistsException of(String loginId) {
		return new MemberAlreadyExistsException("이미 사용 중인 아이디입니다.");
	}
}
