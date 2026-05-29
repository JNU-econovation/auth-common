package com.econo.auth.api.adapter.in.web;

import com.econo.auth.api.application.LoginTokenService;
import com.econo.auth.api.application.LoginTokenService.TokenPair;
import com.econo.auth.core.member.application.port.out.MemberRepository;
import com.econo.auth.core.member.domain.Member;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AT/RT 재발급 컨트롤러 — EEOS-BE V1 reissue 패턴과 동일
 *
 * <p>WEB: RT를 HttpOnly 쿠키에서 읽어 재발급, APP: 요청 바디의 RT를 사용
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class ReissueController {

	private static final String CLIENT_TYPE_HEADER = "Client-Type";

	private final LoginTokenService loginTokenService;
	private final JwtDecoder jwtDecoder;
	private final MemberRepository memberRepository;
	private final TokenCookieManager cookieManager;

	/** AT/RT 재발급 */
	@PostMapping("/reissue")
	public ResponseEntity<?> reissue(
			@RequestHeader(value = CLIENT_TYPE_HEADER, defaultValue = "WEB") String clientType,
			@RequestBody(required = false) ReissueRequest body,
			HttpServletRequest request,
			HttpServletResponse response) {
		String rawRt = resolveRefreshToken(clientType, body, request);
		if (rawRt == null || rawRt.isBlank()) {
			return ResponseEntity.status(401)
					.body(new ErrorResponse("REFRESH_TOKEN_MISSING", "Refresh token이 없습니다."));
		}

		Jwt jwt;
		try {
			jwt = jwtDecoder.decode(rawRt);
		} catch (JwtException e) {
			return ResponseEntity.status(401)
					.body(new ErrorResponse("REFRESH_TOKEN_INVALID", "유효하지 않은 Refresh token입니다."));
		}

		Long memberId;
		try {
			memberId = loginTokenService.extractMemberIdFromRt(jwt);
		} catch (IllegalArgumentException e) {
			return ResponseEntity.status(401)
					.body(new ErrorResponse("REFRESH_TOKEN_INVALID", "Access token으로 재발급 불가합니다."));
		}

		Member member =
				memberRepository
						.findById(memberId)
						.orElseThrow(
								() ->
										new org.springframework.web.server.ResponseStatusException(
												org.springframework.http.HttpStatus.UNAUTHORIZED));

		TokenPair tokens = loginTokenService.reissue(member);
		boolean isApp = "APP".equalsIgnoreCase(clientType);

		LoginResponse responseBody;
		if (isApp) {
			responseBody = LoginResponse.app(tokens.accessToken(), tokens.accessExpiredAt(), tokens.refreshToken());
		} else {
			cookieManager.setRtCookie(response, tokens.refreshToken());
			responseBody = LoginResponse.web(tokens.accessToken(), tokens.accessExpiredAt());
		}

		return ResponseEntity.ok(responseBody);
	}

	/** 로그아웃 — WEB: RT 쿠키 삭제 */
	@PostMapping("/logout")
	public ResponseEntity<Void> logout(
			@RequestHeader(value = CLIENT_TYPE_HEADER, defaultValue = "WEB") String clientType,
			HttpServletResponse response) {
		if (!"APP".equalsIgnoreCase(clientType)) {
			cookieManager.deleteRtCookie(response);
		}
		return ResponseEntity.ok().build();
	}

	private String resolveRefreshToken(String clientType, ReissueRequest body, HttpServletRequest request) {
		if ("APP".equalsIgnoreCase(clientType)) {
			return body != null ? body.refreshToken() : null;
		}
		return cookieManager.extractRtFromCookie(request).orElse(null);
	}

	record ReissueRequest(String refreshToken) {}

	record ErrorResponse(String errorCode, String message) {}
}
