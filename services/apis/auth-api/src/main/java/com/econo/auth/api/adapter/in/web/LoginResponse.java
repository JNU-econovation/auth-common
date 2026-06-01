package com.econo.auth.api.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 로그인/재발급 응답 DTO
 *
 * <p>EEOS-BE V1LoginController 응답 형식과 동일:
 *
 * <ul>
 *   <li>WEB: {@code accessToken} + {@code accessExpiredTime} (RT는 HttpOnly 쿠키)
 *   <li>APP: {@code accessToken} + {@code accessExpiredTime} + {@code refreshToken}
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoginResponse(String accessToken, Long accessExpiredTime, String refreshToken) {

	/** WEB: AT는 쿠키로 발급됨. body에는 만료시간만 (프론트가 갱신 타이밍 파악용) */
	public static LoginResponse web(long accessExpiredTime) {
		return new LoginResponse(null, accessExpiredTime, null);
	}

	public static LoginResponse app(String accessToken, long accessExpiredTime, String refreshToken) {
		return new LoginResponse(accessToken, accessExpiredTime, refreshToken);
	}
}
