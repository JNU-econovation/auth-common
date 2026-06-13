package com.econo.auth.api.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 라우트 목록 응답 DTO
 *
 * @param routes 라우트 결과 목록
 */
@Schema(description = "전체 라우트 목록 응답")
public record RouteListResponse(@Schema(description = "등록된 라우트 목록") List<RouteResponse> routes) {}
