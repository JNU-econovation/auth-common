package com.econo.auth.api.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 로그인/재발급 응답 DTO
 *
 * <p>EEOS-BE V1LoginController 응답 형식과 동일:
 *
 * <ul>
 *   <li>WEB: {@code accessToken} + {@code accessExpiredTime} (RT는 HttpOnly 쿠키)
 *   <li>APP: {@code accessToken} + {@code accessExpiredTime} + {@code refreshToken} + {@code
 *       redirectUrl}
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoginResponse(
		String accessToken, Long accessExpiredTime, String refreshToken, String redirectUrl) {

	/**
	 * WEB: AT는 쿠키로 발급됨. body에는 만료시간만 (프론트가 갱신 타이밍 파악용)
	 *
	 * @deprecated WEB 로그인 분기가 302 리다이렉트로 전환되어 body 직렬화가 불필요해짐.
	 */
	@Deprecated
	public static LoginResponse web(long accessExpiredTime) {
		return new LoginResponse(null, accessExpiredTime, null, null);
	}

	/**
	 * APP: AT + RT 모두 body (하위 호환 — redirectUrl=null)
	 *
	 * @param accessToken 액세스 토큰
	 * @param accessExpiredTime 액세스 토큰 만료 시각 (epoch millis)
	 * @param refreshToken 리프레시 토큰
	 * @return LoginResponse 인스턴스
	 */
	public static LoginResponse app(String accessToken, long accessExpiredTime, String refreshToken) {
		return new LoginResponse(accessToken, accessExpiredTime, refreshToken, null);
	}

	/**
	 * APP: AT + RT + redirectUrl 모두 body
	 *
	 * @param accessToken 액세스 토큰
	 * @param accessExpiredTime 액세스 토큰 만료 시각 (epoch millis)
	 * @param refreshToken 리프레시 토큰
	 * @param redirectUrl 로그인 후 이동 목적지 URL
	 * @return LoginResponse 인스턴스
	 */
	public static LoginResponse app(
			String accessToken, long accessExpiredTime, String refreshToken, String redirectUrl) {
		return new LoginResponse(accessToken, accessExpiredTime, refreshToken, redirectUrl);
	}
}
