package com.econo.auth.api.presentation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 로그인/재발급 응답 DTO
 *
 * <p>EEOS-BE V1LoginController 응답 형식과 동일:
 *
 * <ul>
 *   <li>WEB: AT + RT HttpOnly 쿠키 발급. body에는 {@code redirectUrl}만 포함 (accessExpiredTime 제외 — WEB은
 *       AT가 HttpOnly 쿠키라 FE가 만료시간을 직접 사용할 수 없으며, 갱신은 401 → /reissue reactive로 처리).
 *   <li>APP: {@code accessToken} + {@code accessExpiredTime} + {@code refreshToken} + {@code
 *       redirectUrl}
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoginResponse(
		@Schema(
						description = "Access Token (JWT). WEB에서는 쿠키로 전달되므로 null.",
						nullable = true,
						example = "eyJhbGciOiJSUzI1NiJ9...")
				String accessToken,
		@Schema(
						description = "Access Token 만료 시각 (epoch millis). WEB에서는 null(쿠키 전용).",
						nullable = true,
						example = "1749902400000")
				Long accessExpiredTime,
		@Schema(
						description = "Refresh Token. WEB에서는 쿠키로 전달되므로 null.",
						nullable = true,
						example = "eyJhbGciOiJSUzI1NiJ9...")
				String refreshToken,
		@Schema(
						description = "로그인 후 이동 목적지 URL (nullable)",
						nullable = true,
						example = "https://app.example.com/dashboard")
				String redirectUrl) {

	/**
	 * WEB: AT + RT HttpOnly 쿠키 발급. body에는 {@code redirectUrl}만 포함.
	 *
	 * <p>accessToken/accessExpiredTime/refreshToken은 모두 null — AT/RT는 쿠키 전용이며 FE는 만료시간을 직접 사용할 수
	 * 없다(갱신은 401 → /reissue reactive로 처리).
	 *
	 * @param redirectUrl 로그인 후 이동 목적지 URL
	 * @return LoginResponse 인스턴스
	 */
	public static LoginResponse web(String redirectUrl) {
		return new LoginResponse(null, null, null, redirectUrl);
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
