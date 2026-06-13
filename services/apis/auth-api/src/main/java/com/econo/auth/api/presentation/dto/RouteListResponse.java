package com.econo.auth.api.presentation.dto;

import java.util.List;

/**
 * 라우트 목록 응답 DTO
 *
 * @param routes 라우트 결과 목록
 */
public record RouteListResponse(List<RouteResponse> routes) {}
