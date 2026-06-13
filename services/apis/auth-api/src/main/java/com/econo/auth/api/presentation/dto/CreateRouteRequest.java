package com.econo.auth.api.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 라우트 등록 요청 DTO
 *
 * @param pathPrefix 경로 접두사 (필수, blank 불가)
 * @param upstreamUrl 업스트림 서비스 URL (필수, blank 불가, SSRF 검증 대상)
 * @param enabled 활성화 여부 (필수)
 */
public record CreateRouteRequest(
		@NotBlank @Schema(description = "게이트웨이 라우팅 경로 접두사", example = "/api/v1/myservice")
				String pathPrefix,
		@NotBlank @Schema(description = "업스트림 서비스 URL (SSRF 검증 대상)", example = "http://myservice:8080")
				String upstreamUrl,
		@NotNull @Schema(description = "라우트 활성화 여부", example = "true") Boolean enabled) {}
