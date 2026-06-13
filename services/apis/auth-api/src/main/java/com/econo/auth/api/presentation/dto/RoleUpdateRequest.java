package com.econo.auth.api.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/** 역할 변경 공용 요청 DTO */
public record RoleUpdateRequest(
		@NotBlank @Schema(description = "변경할 역할", example = "ADMIN") String role) {}
