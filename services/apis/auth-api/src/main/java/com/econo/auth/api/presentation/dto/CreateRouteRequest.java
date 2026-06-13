package com.econo.auth.api.presentation.dto;

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
		@NotBlank String pathPrefix, @NotBlank String upstreamUrl, @NotNull Boolean enabled) {}
