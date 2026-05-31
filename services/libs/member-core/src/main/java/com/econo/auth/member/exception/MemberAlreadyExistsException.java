package com.econo.auth.member.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/** loginId 중복 예외 (HTTP 409) */
@Getter
public class MemberAlreadyExistsException extends RuntimeException {

	private final HttpStatus httpStatus = HttpStatus.CONFLICT;
	private final String errorCode = "MEMBER_ALREADY_EXISTS";

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
