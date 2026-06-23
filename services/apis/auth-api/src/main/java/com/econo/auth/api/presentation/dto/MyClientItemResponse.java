package com.econo.auth.api.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Set;

/**
 * 셀프 클라이언트 목록 단건 / 상세 공용 응답 DTO
 *
 * <p>clientSecret은 절대 포함하지 않는다 (1회 노출 후 재조회 불가 정책).
 */
public record MyClientItemResponse(
		@Schema(description = "OAuth 클라이언트 ID (UUID)", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
				String clientId,
		@Schema(description = "OAuth 클라이언트 이름", example = "에코노 SPA") String clientName,
		@Schema(description = "허용 리다이렉트 URI 목록", example = "[\"https://app.econovation.kr/callback\"]")
				Set<String> redirectUris,
		@Schema(description = "연결된 Gateway 라우트 정보 (라우트 없는 클라이언트는 null)", nullable = true)
				MyClientRouteInfo route) {}
