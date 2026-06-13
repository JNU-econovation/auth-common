package com.econo.auth.api.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** OAuth 클라이언트 셀프 등록 응답 DTO */
public record SelfRegisterClientResponse(
		@Schema(
						description = "발급된 OAuth 클라이언트 ID (UUID)",
						example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
				String clientId,
		@Schema(
						description = "1회 반환되는 클라이언트 시크릿 (재조회 불가)",
						example = "sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx")
				String clientSecret) {}
