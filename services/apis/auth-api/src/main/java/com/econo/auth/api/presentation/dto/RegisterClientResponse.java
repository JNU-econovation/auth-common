package com.econo.auth.api.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 어드민 OAuth 클라이언트 등록 응답 DTO */
public record RegisterClientResponse(
		@Schema(
						description = "발급된 OAuth 클라이언트 ID (UUID)",
						example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
				String clientId) {}
