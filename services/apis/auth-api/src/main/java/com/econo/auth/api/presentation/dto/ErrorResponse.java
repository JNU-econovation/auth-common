package com.econo.auth.api.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/** 공용 에러 응답 DTO — 에러 코드·메시지·타임스탬프를 담는다. timestamp는 항상 포함. */
public record ErrorResponse(
		@Schema(description = "에러 코드", example = "INVALID_ROLE") String errorCode,
		@Schema(description = "에러 메시지", example = "유효하지 않은 역할입니다.") String message,
		@Schema(description = "에러 발생 시각", example = "2026-06-14T10:00:00") LocalDateTime timestamp) {

	/**
	 * timestamp를 자동으로 현재 시각으로 채우는 편의 생성자.
	 *
	 * @param errorCode 에러 코드
	 * @param message 에러 메시지
	 */
	public ErrorResponse(String errorCode, String message) {
		this(errorCode, message, LocalDateTime.now());
	}
}
