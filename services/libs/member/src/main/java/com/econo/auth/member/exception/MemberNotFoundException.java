package com.econo.auth.member.exception;

/** 회원 조회 실패 예외 — HTTP 매핑은 GlobalExceptionHandler가 담당 */
public class MemberNotFoundException extends RuntimeException {

	public static final String ERROR_CODE = "MEMBER_NOT_FOUND";

	private MemberNotFoundException(String message) {
		super(message);
	}

	/**
	 * 회원 미존재 예외 생성
	 *
	 * @param memberId 조회 시도한 회원 PK
	 * @return MemberNotFoundException 인스턴스
	 */
	public static MemberNotFoundException of(Long memberId) {
		return new MemberNotFoundException("회원을 찾을 수 없습니다: " + memberId);
	}
}
