package com.econo.auth.api.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 라우트 단건 응답 DTO
 *
 * @param routeId 라우트 UUID 문자열
 * @param pathPrefix 경로 접두사
 * @param upstreamUrl 업스트림 서비스 URL
 * @param enabled 활성화 여부
 * @param createdAt 생성 시각
 * @param updatedAt 수정 시각
 */
public record RouteResponse(
		@Schema(description = "라우트 UUID", example = "550e8400-e29b-41d4-a716-446655440000")
				String routeId,
		@Schema(description = "경로 접두사", example = "/api/v1/myservice") String pathPrefix,
		@Schema(description = "업스트림 서비스 URL", example = "http://myservice:8080") String upstreamUrl,
		@Schema(description = "활성화 여부", example = "true") boolean enabled,
		@Schema(description = "생성 시각", example = "2026-06-14T10:00:00") LocalDateTime createdAt,
		@Schema(description = "최종 수정 시각", example = "2026-06-14T11:30:00") LocalDateTime updatedAt) {}
