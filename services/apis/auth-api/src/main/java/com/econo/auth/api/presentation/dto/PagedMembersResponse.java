package com.econo.auth.api.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 어드민 회원 목록 페이지 응답 DTO */
public record PagedMembersResponse(
		@Schema(description = "현재 페이지 회원 목록") List<MemberSummary> content,
		@Schema(description = "전체 회원 수", example = "150") long totalElements,
		@Schema(description = "전체 페이지 수", example = "8") int totalPages,
		@Schema(description = "현재 페이지 번호 (0-based)", example = "0") int page,
		@Schema(description = "페이지 크기", example = "20") int size) {}
