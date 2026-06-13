package com.econo.auth.api.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Set;

/** 클라이언트 상세 조회 응답 DTO */
public record ClientDetailResponse(
		@Schema(description = "OAuth 클라이언트 ID", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
				String clientId,
		@Schema(description = "OAuth 클라이언트 이름", example = "에코노 SPA") String clientName,
		@Schema(description = "등록된 리다이렉트 URI 목록", example = "[\"https://app.example.com/callback\"]")
				Set<String> redirectUris) {}
