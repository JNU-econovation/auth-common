package com.econo.auth.api.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 클라이언트에 연결된 Gateway 라우트 정보 응답 DTO (null 허용 — 라우트 없는 클라이언트는 route 자체가 null) */
public record MyClientRouteInfo(
		@Schema(
						description = "라우트 ID (UUID)",
						nullable = true,
						example = "r1b2c3d4-e5f6-7890-abcd-ef1234567890")
				String routeId,
		@Schema(description = "경로 접두사", nullable = true, example = "/api/my-service/**")
				String pathPrefix,
		@Schema(
						description = "업스트림 서비스 URL",
						nullable = true,
						example = "https://my-service.example.com")
				String upstreamUrl,
		@Schema(description = "라우트 활성화 여부", nullable = true) Boolean enabled) {}
