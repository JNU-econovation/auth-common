package com.econo.auth.api.presentation.util;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * AT/RT HttpOnly 쿠키 관리
 *
 * <p>WEB 클라이언트용: AT + RT 모두 HttpOnly SameSite=None 쿠키로 발급.<br>
 * Domain=.econovation.kr 설정 시 모든 서브도메인에서 공유 → 자동 SSO.
 */
@Component
public class TokenCookieManager {

	static final String AT_COOKIE = "at";
	static final String RT_COOKIE = "rt";

	@Value("${auth.cookie.domain:}")
	private String cookieDomain;

	@Value("${auth.cookie.secure:true}")
	private boolean secure;

	@Value("${auth.token.at-expiry-seconds:3600}")
	private long atExpirySeconds;

	@Value("${auth.token.rt-expiry-seconds:2592000}")
	private long rtExpirySeconds;

	public void setAtCookie(HttpServletResponse response, String accessToken) {
		ResponseCookie cookie =
				ResponseCookie.from(AT_COOKIE, accessToken)
						.httpOnly(true)
						.secure(secure)
						.sameSite("None")
						.path("/")
						.maxAge(atExpirySeconds)
						.domain(cookieDomain.isBlank() ? null : cookieDomain)
						.build();
		response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
	}

	public void deleteAtCookie(HttpServletResponse response) {
		ResponseCookie cookie =
				ResponseCookie.from(AT_COOKIE, "")
						.httpOnly(true)
						.secure(secure)
						.sameSite("None")
						.path("/")
						.maxAge(0)
						.domain(cookieDomain.isBlank() ? null : cookieDomain)
						.build();
		response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
	}

	public Optional<String> extractAtFromCookie(HttpServletRequest request) {
		if (request.getCookies() == null) return Optional.empty();
		return Arrays.stream(request.getCookies())
				.filter(c -> AT_COOKIE.equals(c.getName()))
				.map(Cookie::getValue)
				.findFirst();
	}

	public void setRtCookie(HttpServletResponse response, String refreshToken) {
		ResponseCookie cookie =
				ResponseCookie.from(RT_COOKIE, refreshToken)
						.httpOnly(true)
						.secure(secure)
						.sameSite("None")
						.path("/")
						.maxAge(rtExpirySeconds)
						.domain(cookieDomain.isBlank() ? null : cookieDomain)
						.build();
		response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
	}

	public void deleteRtCookie(HttpServletResponse response) {
		ResponseCookie cookie =
				ResponseCookie.from(RT_COOKIE, "")
						.httpOnly(true)
						.secure(secure)
						.sameSite("None")
						.path("/")
						.maxAge(0)
						.domain(cookieDomain.isBlank() ? null : cookieDomain)
						.build();
		response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
	}

	public Optional<String> extractRtFromCookie(HttpServletRequest request) {
		if (request.getCookies() == null) return Optional.empty();
		return Arrays.stream(request.getCookies())
				.filter(c -> RT_COOKIE.equals(c.getName()))
				.map(Cookie::getValue)
				.findFirst();
	}
}
