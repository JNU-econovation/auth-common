package com.econo.auth.core.member.exception;

/** 회원 조회 실패 예외 — HTTP 매핑은 GlobalExceptionHandler가 담당 */
public class MemberNotFoundException extends RuntimeException {

	public static final String ERROR_CODE = "MEMBER_NOT_FOUND";

	public MemberNotFoundException(Long memberId) {
		super("회원을 찾을 수 없습니다: " + memberId);
	}
}
