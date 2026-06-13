package com.econo.auth.login.application.domain;

import java.util.Map;

/**
 * {@link com.econo.auth.login.application.repository.TokenDecoder#decode(String)} 반환 값
 *
 * <p>subject와 claims 맵만 담는 불변 데이터 객체. Spring/JWT 타입 의존 없음.
 *
 * @param subject 토큰 주체 (memberId 문자열)
 * @param claims 클레임 맵 (방어적 복사됨)
 */
public record DecodedToken(String subject, Map<String, Object> claims) {

	/**
	 * 방어적 복사 생성자 — claims는 {@link Map#copyOf(Map)}로 불변 복사된다
	 *
	 * @param subject 토큰 주체 (memberId 문자열)
	 * @param claims 클레임 맵
	 */
	public DecodedToken {
		claims = claims != null ? Map.copyOf(claims) : Map.of();
	}
}
