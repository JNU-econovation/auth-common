package com.econo.auth.api.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 단건/다건 회원 조회 요청 DTO */
public record MemberQueryRequest(
		@NotEmpty(message = "ids는 1개 이상이어야 합니다.")
				@Size(max = 1000, message = "한 번에 최대 1000개까지 조회할 수 있습니다.")
				@Schema(description = "조회할 회원 ID 목록 (1개 이상, 최대 1000개)", example = "[1, 2, 42]")
				List<Long> ids) {}
