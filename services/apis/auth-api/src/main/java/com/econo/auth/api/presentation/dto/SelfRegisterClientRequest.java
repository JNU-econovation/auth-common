package com.econo.auth.api.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Set;

/** OAuth 클라이언트 셀프 등록 요청 DTO */
public record SelfRegisterClientRequest(
		@NotBlank @Schema(description = "OAuth 클라이언트 이름", example = "에코노 SPA") String clientName,
		@NotNull
				@Schema(description = "허용할 리다이렉트 URI 목록", example = "[\"http://localhost:3000/callback\"]")
				Set<String> redirectUris,
		@Schema(description = "라우트 경로 접두사 (예: /api/my-service/**)", nullable = true) String pathPrefix,
		@Schema(description = "업스트림 서비스 URL (예: https://my-service.example.com)", nullable = true)
				String upstreamUrl) {

	/**
	 * pathPrefix와 upstreamUrl은 둘 다 있거나 둘 다 없어야 한다 (Bean Validation 클래스 레벨 검증).
	 *
	 * <p>하나만 있는 경우 {@code MethodArgumentNotValidException} → 400 VALIDATION_FAILED.
	 *
	 * @return 두 필드 모두 있거나 모두 없으면 true, 아니면 false
	 */
	@Schema(hidden = true)
	@AssertTrue(message = "pathPrefix와 upstreamUrl은 둘 다 있거나 둘 다 없어야 합니다.")
	public boolean isRouteFields() {
		boolean hasPrefix = pathPrefix != null && !pathPrefix.isBlank();
		boolean hasUpstream = upstreamUrl != null && !upstreamUrl.isBlank();
		return hasPrefix == hasUpstream;
	}
}
