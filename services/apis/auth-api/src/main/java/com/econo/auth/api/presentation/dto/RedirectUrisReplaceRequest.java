package com.econo.auth.api.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.Set;

/** redirectUri 전체 교체 요청 DTO */
public record RedirectUrisReplaceRequest(
		@NotNull
				@Schema(
						description = "새로 설정할 리다이렉트 URI 전체 목록",
						example =
								"[\"https://app.example.com/callback\", \"https://staging.example.com/callback\"]")
				Set<String> uris) {}
