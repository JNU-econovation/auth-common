package com.econo.auth.api.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 셀프 클라이언트 목록 응답 래퍼 DTO */
public record MyClientListResponse(
		@Schema(description = "내 OAuth 클라이언트 목록") List<MyClientItemResponse> clients) {}
