package com.econo.auth.api.presentation.controller;

import com.econo.auth.api.application.usecase.LoginTokenUseCase;
import com.econo.auth.api.application.usecase.LoginTokenUseCase.TokenPair;
import com.econo.auth.api.presentation.dto.LoginResponse;
import com.econo.auth.api.presentation.util.TokenCookieManager;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
 * AT/RT 재발급 컨트롤러
 *
 * <p>WEB: RT를 HttpOnly 쿠키에서 읽어 재발급, APP: 요청 바디의 RT를 사용. Member 조회는 {@link LoginTokenUseCase}에 위임한다.
 */
@Tag(name = "Auth — Token Reissue & Logout", description = "AT/RT 재발급 및 로그아웃 API")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class ReissueController {

	private static final String CLIENT_TYPE_HEADER = "Client-Type";

	private final LoginTokenUseCase loginTokenUseCase;
	private final JwtDecoder jwtDecoder;
	private final TokenCookieManager cookieManager;

	@Operation(
			summary = "AT/RT 재발급",
			description =
					"Refresh Token으로 새 Access Token과 Refresh Token을 발급한다.\n\n"
							+ "**WEB** (`Client-Type: WEB`, 기본): RT를 HttpOnly 쿠키(`rt`)에서 읽음. 새 RT를 쿠키로 반환.\n"
							+ "**APP** (`Client-Type: APP`): RT를 요청 body(`refreshToken`)에서 읽음. 새 RT도 body로 반환.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "재발급 성공"),
		@ApiResponse(
				responseCode = "401",
				description = "RT 없음, 만료, 또는 access token으로 재발급 시도",
				content = @Content)
	})
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
			memberId = loginTokenUseCase.extractMemberIdFromRt(jwt);
		} catch (IllegalArgumentException e) {
			return ResponseEntity.status(401)
					.body(new ErrorResponse("REFRESH_TOKEN_INVALID", "Access token으로 재발급 불가합니다."));
		}

		// Member 조회 + 토큰 재발급을 LoginTokenUseCase에 위임
		TokenPair tokens = loginTokenUseCase.reissue(memberId);
		boolean isApp = "APP".equalsIgnoreCase(clientType);

		LoginResponse responseBody;
		if (isApp) {
			responseBody =
					LoginResponse.app(tokens.accessToken(), tokens.accessExpiredAt(), tokens.refreshToken());
		} else {
			cookieManager.setAtCookie(response, tokens.accessToken());
			cookieManager.setRtCookie(response, tokens.refreshToken());
			responseBody = LoginResponse.web(tokens.accessExpiredAt());
		}

		return ResponseEntity.ok(responseBody);
	}

	@Operation(
			summary = "로그아웃",
			description =
					"**WEB**: `at`/`rt` HttpOnly 쿠키를 Max-Age=0으로 만료시킨다.\n**APP**: 클라이언트가 RT를 직접 삭제한다.")
	@ApiResponse(responseCode = "200", description = "로그아웃 성공 (멱등 — RT 없어도 200)")
	@PostMapping("/logout")
	public ResponseEntity<Void> logout(
			@RequestHeader(value = CLIENT_TYPE_HEADER, defaultValue = "WEB") String clientType,
			HttpServletResponse response) {
		if (!"APP".equalsIgnoreCase(clientType)) {
			cookieManager.deleteAtCookie(response);
			cookieManager.deleteRtCookie(response);
		}
		return ResponseEntity.ok().build();
	}

	private String resolveRefreshToken(
			String clientType, ReissueRequest body, HttpServletRequest request) {
		if ("APP".equalsIgnoreCase(clientType)) {
			return body != null ? body.refreshToken() : null;
		}
		return cookieManager.extractRtFromCookie(request).orElse(null);
	}

	record ReissueRequest(String refreshToken) {}

	record ErrorResponse(String errorCode, String message) {}
}
