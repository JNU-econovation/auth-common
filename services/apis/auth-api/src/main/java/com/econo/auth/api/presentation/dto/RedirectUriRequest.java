package com.econo.auth.api.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/** redirectUri 단건 추가/제거 요청 DTO */
public record RedirectUriRequest(
		@NotBlank
				@Schema(description = "추가 또는 제거할 리다이렉트 URI", example = "https://app.example.com/callback")
				String uri) {}
