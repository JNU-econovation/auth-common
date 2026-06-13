package com.econo.auth.api.presentation.dto;

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
		String routeId,
		String pathPrefix,
		String upstreamUrl,
		boolean enabled,
		LocalDateTime createdAt,
		LocalDateTime updatedAt) {}
