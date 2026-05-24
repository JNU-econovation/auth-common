package com.econo.auth.core.member.application.port.out;

import com.econo.auth.core.member.domain.Member;

/**
 * JWT 발급 아웃바운드 포트
 *
 * <p>JWT 클레임 계약: sub(memberId), loginId, name, generation, status, roles(["USER"]), iat, exp
 */
public interface TokenIssuer {

	/**
	 * 회원 정보로 JWT 발급
	 *
	 * @param member 회원 도메인 객체
	 * @return 서명된 JWT 문자열
	 */
	String issue(Member member);
}
