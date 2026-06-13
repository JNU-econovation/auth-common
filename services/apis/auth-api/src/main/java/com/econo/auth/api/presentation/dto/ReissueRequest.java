package com.econo.auth.api.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** APP 클라이언트용 리프레시 토큰 재발급 요청 DTO */
public record ReissueRequest(
		@Schema(
						description = "APP 클라이언트가 바디로 전달하는 Refresh Token. WEB은 쿠키 사용이므로 null 허용.",
						nullable = true,
						example = "eyJhbGciOiJSUzI1NiJ9...")
				String refreshToken) {}
