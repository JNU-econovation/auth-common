package com.econo.auth.api.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.util.Set;

/** 어드민 OAuth 클라이언트 등록 요청 DTO */
public record RegisterClientRequest(
		@NotBlank @Schema(description = "OAuth 클라이언트 이름", example = "에코노 SPA") String clientName,
		@Schema(description = "허용할 리다이렉트 URI 목록", example = "[\"http://localhost:3000/callback\"]")
				Set<String> redirectUris) {}
