package com.econo.auth.api.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Set;

/** redirectUri 추가/제거/교체 후 응답 DTO */
public record RedirectUrisResponse(
		@Schema(description = "OAuth 클라이언트 ID", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
				String clientId,
		@Schema(
						description = "변경 후 전체 리다이렉트 URI 목록",
						example = "[\"https://app.example.com/callback\"]")
				Set<String> redirectUris) {}
