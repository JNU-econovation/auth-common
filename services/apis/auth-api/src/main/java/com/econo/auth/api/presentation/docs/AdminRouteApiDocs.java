package com.econo.auth.api.presentation.docs;

import com.econo.auth.api.presentation.dto.CreateRouteRequest;
import com.econo.auth.api.presentation.dto.RouteListResponse;
import com.econo.auth.api.presentation.dto.RouteResponse;
import com.econo.auth.api.presentation.dto.UpdateRouteRequest;
import com.econo.common.auth.core.passport.Passport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

/** AdminRouteController 의 Swagger 문서 정의. 어노테이션만 보유하며 메서드 규격은 컨트롤러와 동일하다. */
@Tag(name = "Admin")
public interface AdminRouteApiDocs {

	@Operation(
			summary = "동적 라우트 등록",
			description = "새 동적 라우트를 등록하고 api-gateway에 즉시 반영 트리거. ADMIN/SUPER_ADMIN 전용.",
			security = @SecurityRequirement(name = "cookieAuth"))
	@ApiResponses({
		@ApiResponse(responseCode = "201", description = "라우트 등록 성공"),
		@ApiResponse(
				responseCode = "400",
				description = "VALIDATION_FAILED / ROUTE_UPSTREAM_INVALID",
				content = @Content),
		@ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED", content = @Content),
		@ApiResponse(
				responseCode = "403",
				description = "FORBIDDEN / ROUTE_PROTECTED",
				content = @Content),
		@ApiResponse(responseCode = "409", description = "ROUTE_PATH_CONFLICT", content = @Content)
	})
	ResponseEntity<RouteResponse> createRoute(Passport passport, CreateRouteRequest request);

	@Operation(
			summary = "전체 라우트 목록 조회",
			description = "등록된 전체 라우트 목록을 조회한다. ADMIN/SUPER_ADMIN 전용.",
			security = @SecurityRequirement(name = "cookieAuth"))
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "목록 조회 성공"),
		@ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED", content = @Content),
		@ApiResponse(responseCode = "403", description = "FORBIDDEN", content = @Content)
	})
	ResponseEntity<RouteListResponse> listRoutes(Passport passport);

	@Operation(
			summary = "단건 라우트 조회",
			description = "특정 routeId에 해당하는 라우트를 조회한다. ADMIN/SUPER_ADMIN 전용.",
			security = @SecurityRequirement(name = "cookieAuth"))
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "단건 조회 성공"),
		@ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED", content = @Content),
		@ApiResponse(responseCode = "403", description = "FORBIDDEN", content = @Content),
		@ApiResponse(responseCode = "404", description = "ROUTE_NOT_FOUND", content = @Content)
	})
	ResponseEntity<RouteResponse> getRoute(Passport passport, String routeId);

	@Operation(
			summary = "라우트 수정",
			description =
					"기존 라우트의 pathPrefix, upstreamUrl, enabled를 변경한다. 변경 후 게이트웨이 즉시 반영. ADMIN/SUPER_ADMIN 전용.",
			security = @SecurityRequirement(name = "cookieAuth"))
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "수정 성공"),
		@ApiResponse(
				responseCode = "400",
				description = "VALIDATION_FAILED / ROUTE_UPSTREAM_INVALID",
				content = @Content),
		@ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED", content = @Content),
		@ApiResponse(
				responseCode = "403",
				description = "FORBIDDEN / ROUTE_PROTECTED",
				content = @Content),
		@ApiResponse(responseCode = "404", description = "ROUTE_NOT_FOUND", content = @Content),
		@ApiResponse(responseCode = "409", description = "ROUTE_PATH_CONFLICT", content = @Content)
	})
	ResponseEntity<RouteResponse> updateRoute(
			Passport passport, String routeId, UpdateRouteRequest request);

	@Operation(
			summary = "라우트 삭제",
			description =
					"라우트를 삭제한다. 보호 경로(auth-api 핵심 경로) 삭제 금지. 삭제 후 게이트웨이 즉시 반영. ADMIN/SUPER_ADMIN 전용.",
			security = @SecurityRequirement(name = "cookieAuth"))
	@ApiResponses({
		@ApiResponse(responseCode = "204", description = "삭제 성공"),
		@ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED", content = @Content),
		@ApiResponse(
				responseCode = "403",
				description = "FORBIDDEN / ROUTE_PROTECTED",
				content = @Content),
		@ApiResponse(responseCode = "404", description = "ROUTE_NOT_FOUND", content = @Content)
	})
	ResponseEntity<Void> deleteRoute(Passport passport, String routeId);
}
