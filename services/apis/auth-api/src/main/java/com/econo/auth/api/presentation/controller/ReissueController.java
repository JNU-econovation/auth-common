package com.econo.auth.api.presentation.controller;

import com.econo.auth.api.presentation.docs.ReissueApiDocs;
import com.econo.auth.api.presentation.dto.LoginResponse;
import com.econo.auth.api.presentation.util.TokenCookieManager;
import com.econo.auth.login.application.usecase.LoginTokenUseCase;
import com.econo.auth.login.application.usecase.LoginTokenUseCase.TokenPair;
import com.econo.auth.login.exception.InvalidTokenException;
import com.econo.auth.login.exception.WrongTokenTypeException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
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
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class ReissueController implements ReissueApiDocs {

	private static final String CLIENT_TYPE_HEADER = "Client-Type";

	private final LoginTokenUseCase loginTokenUseCase;
	private final TokenCookieManager cookieManager;

	@Override
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

		Long memberId;
		try {
			memberId = loginTokenUseCase.verifyRefreshTokenAndGetMemberId(rawRt);
		} catch (InvalidTokenException | WrongTokenTypeException e) {
			return ResponseEntity.status(401)
					.body(new ErrorResponse("REFRESH_TOKEN_INVALID", "유효하지 않은 Refresh token입니다."));
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

	@Override
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

	public record ReissueRequest(String refreshToken) {}

	record ErrorResponse(String errorCode, String message) {}
}
