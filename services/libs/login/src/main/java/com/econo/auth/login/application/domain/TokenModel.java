package com.econo.auth.login.application.domain;

import java.time.Instant;
import java.util.Map;

/**
 * AT 또는 RT 인코딩에 필요한 모든 정보를 담는 불변 데이터 객체
 *
 * <p>Spring/JWT 타입 의존 없음. {@link
 * com.econo.auth.login.application.repository.TokenEncoder#encode(TokenModel)} 에 전달되어 JWT 문자열로
 * 변환된다.
 *
 * @param issuer 토큰 발급자 URI
 * @param subject 토큰 주체 (memberId 문자열)
 * @param issuedAt 발급 시각
 * @param expiresAt 만료 시각
 * @param claims 추가 클레임 맵 (방어적 복사됨)
 */
public record TokenModel(
		String issuer,
		String subject,
		Instant issuedAt,
		Instant expiresAt,
		Map<String, Object> claims) {

	/**
	 * 방어적 복사 생성자 — claims는 {@link Map#copyOf(Map)}로 불변 복사된다
	 *
	 * @param issuer 토큰 발급자 URI
	 * @param subject 토큰 주체 (memberId 문자열)
	 * @param issuedAt 발급 시각
	 * @param expiresAt 만료 시각
	 * @param claims 추가 클레임 맵
	 */
	public TokenModel {
		claims = claims != null ? Map.copyOf(claims) : Map.of();
	}
}
