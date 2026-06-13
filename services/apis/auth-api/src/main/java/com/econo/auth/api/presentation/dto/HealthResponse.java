package com.econo.auth.api.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 루트 헬스체크 응답 DTO */
public record HealthResponse(
		@Schema(description = "애플리케이션 이름", example = "auth-api") String application,
		@Schema(description = "기동 시각 (ISO-8601)", example = "2026-06-14T09:00:00.000Z")
				String startedAt,
		@Schema(description = "가동 시간", example = "0일 1시간 17분 47초") String uptime) {}
