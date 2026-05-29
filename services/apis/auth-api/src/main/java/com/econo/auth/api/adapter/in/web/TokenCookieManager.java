package com.econo.auth.api.adapter.in.web;

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
 * RT HttpOnly 쿠키 관리 — EEOS-BE AuthCookieManager와 동일한 패턴
 *
 * <p>WEB 클라이언트용: RT를 HttpOnly SameSite=None 쿠키에 저장하고, 재발급/로그아웃 시 읽거나 삭제한다.
 */
@Component
public class TokenCookieManager {

	static final String RT_COOKIE = "rt";

	@Value("${auth.cookie.domain:}")
	private String cookieDomain;

	@Value("${auth.cookie.secure:true}")
	private boolean secure;

	@Value("${auth.token.rt-expiry-seconds:2592000}")
	private long rtExpirySeconds;

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
