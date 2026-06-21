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
				String clientSecret,
		@Schema(description = "생성된 라우트 ID (UUID)", nullable = true) String routeId,
		@Schema(description = "등록된 경로 접두사", nullable = true, example = "/api/my-service/**")
				String pathPrefix,
		@Schema(
						description = "업스트림 서비스 URL",
						nullable = true,
						example = "https://my-service.example.com")
				String upstreamUrl,
		@Schema(description = "라우트 활성화 여부", nullable = true) Boolean enabled) {}
