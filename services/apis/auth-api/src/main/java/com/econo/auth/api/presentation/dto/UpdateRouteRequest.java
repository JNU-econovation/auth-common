package com.econo.auth.api.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 라우트 수정 요청 DTO (전체 필드 필수 — partial update 지원하지 않음)
 *
 * @param pathPrefix 경로 접두사 (필수, blank 불가, 보호 경로 패턴 및 중복 검증 대상)
 * @param upstreamUrl 업스트림 서비스 URL (필수, blank 불가, SSRF 검증 대상)
 * @param enabled 활성화 여부 (필수)
 */
public record UpdateRouteRequest(
		@NotBlank @Schema(description = "변경할 경로 접두사. 보호 경로 패턴 및 중복 검증 대상", example = "/api/v1/board-v2")
				String pathPrefix,
		@NotBlank
				@Schema(description = "변경할 업스트림 URL. SSRF 검증 대상", example = "http://board-service:8080")
				String upstreamUrl,
		@NotNull @Schema(description = "변경할 활성화 여부", example = "false") Boolean enabled) {}
