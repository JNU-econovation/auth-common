package com.econo.auth.api.presentation.controller;

import com.econo.auth.api.presentation.dto.CreateRouteRequest;
import com.econo.auth.api.presentation.dto.RouteListResponse;
import com.econo.auth.api.presentation.dto.RouteResponse;
import com.econo.auth.api.presentation.dto.UpdateRouteRequest;
import com.econo.auth.client.application.usecase.ManageRouteUseCase;
import com.econo.auth.client.application.usecase.ManageRouteUseCase.CreateRouteCommand;
import com.econo.auth.client.application.usecase.ManageRouteUseCase.RouteResult;
import com.econo.auth.client.application.usecase.ManageRouteUseCase.UpdateRouteCommand;
import com.econo.common.auth.core.passport.Passport;
import com.econo.common.auth.core.passport.Roles;
import com.econo.common.auth.web.annotation.PassportAuth;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 동적 라우트 관리 REST 컨트롤러 (Admin)
 *
 * <p>ADMIN 또는 SUPER_ADMIN 역할이 필요하다. Gateway가 JWT를 검증하고 X-User-Passport 헤더를 주입한다.
 */
@Slf4j
@Tag(
		name = "Admin — Route Management",
		description = "동적 게이트웨이 라우트 CRUD API. ADMIN 역할 필요 (Gateway JWT 인증 후 X-User-Passport 주입).")
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminRouteController {

	private final ManageRouteUseCase manageRouteUseCase;

	@Operation(
			summary = "동적 라우트 등록",
			description = "새 동적 라우트를 등록하고 api-gateway에 즉시 반영 트리거. ADMIN/SUPER_ADMIN 전용.",
			security = @SecurityRequirement(name = "bearerAuth"))
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
	@PostMapping("/routes")
	public ResponseEntity<RouteResponse> createRoute(
			@PassportAuth(requiredRoles = {Roles.ADMIN, Roles.SUPER_ADMIN}) Passport passport,
			@Valid @RequestBody CreateRouteRequest request) {
		CreateRouteCommand command =
				new CreateRouteCommand(request.pathPrefix(), request.upstreamUrl(), request.enabled());
		RouteResult result = manageRouteUseCase.createRoute(command);
		return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(result));
	}

	@Operation(
			summary = "전체 라우트 목록 조회",
			description = "등록된 전체 라우트 목록을 조회한다. ADMIN/SUPER_ADMIN 전용.",
			security = @SecurityRequirement(name = "bearerAuth"))
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "목록 조회 성공"),
		@ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED", content = @Content),
		@ApiResponse(responseCode = "403", description = "FORBIDDEN", content = @Content)
	})
	@GetMapping("/routes")
	public ResponseEntity<RouteListResponse> listRoutes(
			@PassportAuth(requiredRoles = {Roles.ADMIN, Roles.SUPER_ADMIN}) Passport passport) {
		List<RouteResult> results = manageRouteUseCase.listRoutes();
		List<RouteResponse> responses = results.stream().map(this::toResponse).toList();
		return ResponseEntity.ok(new RouteListResponse(responses));
	}

	@Operation(
			summary = "단건 라우트 조회",
			description = "특정 routeId에 해당하는 라우트를 조회한다. ADMIN/SUPER_ADMIN 전용.",
			security = @SecurityRequirement(name = "bearerAuth"))
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "단건 조회 성공"),
		@ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED", content = @Content),
		@ApiResponse(responseCode = "403", description = "FORBIDDEN", content = @Content),
		@ApiResponse(responseCode = "404", description = "ROUTE_NOT_FOUND", content = @Content)
	})
	@GetMapping("/routes/{routeId}")
	public ResponseEntity<RouteResponse> getRoute(
			@PassportAuth(requiredRoles = {Roles.ADMIN, Roles.SUPER_ADMIN}) Passport passport,
			@PathVariable String routeId) {
		RouteResult result = manageRouteUseCase.getRoute(routeId);
		return ResponseEntity.ok(toResponse(result));
	}

	@Operation(
			summary = "라우트 수정",
			description =
					"기존 라우트의 pathPrefix, upstreamUrl, enabled를 변경한다. 변경 후 게이트웨이 즉시 반영. ADMIN/SUPER_ADMIN 전용.",
			security = @SecurityRequirement(name = "bearerAuth"))
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
	@PutMapping("/routes/{routeId}")
	public ResponseEntity<RouteResponse> updateRoute(
			@PassportAuth(requiredRoles = {Roles.ADMIN, Roles.SUPER_ADMIN}) Passport passport,
			@PathVariable String routeId,
			@Valid @RequestBody UpdateRouteRequest request) {
		UpdateRouteCommand command =
				new UpdateRouteCommand(request.pathPrefix(), request.upstreamUrl(), request.enabled());
		RouteResult result = manageRouteUseCase.updateRoute(routeId, command);
		return ResponseEntity.ok(toResponse(result));
	}

	@Operation(
			summary = "라우트 삭제",
			description =
					"라우트를 삭제한다. 보호 경로(auth-api 핵심 경로) 삭제 금지. 삭제 후 게이트웨이 즉시 반영. ADMIN/SUPER_ADMIN 전용.",
			security = @SecurityRequirement(name = "bearerAuth"))
	@ApiResponses({
		@ApiResponse(responseCode = "204", description = "삭제 성공"),
		@ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED", content = @Content),
		@ApiResponse(
				responseCode = "403",
				description = "FORBIDDEN / ROUTE_PROTECTED",
				content = @Content),
		@ApiResponse(responseCode = "404", description = "ROUTE_NOT_FOUND", content = @Content)
	})
	@DeleteMapping("/routes/{routeId}")
	public ResponseEntity<Void> deleteRoute(
			@PassportAuth(requiredRoles = {Roles.ADMIN, Roles.SUPER_ADMIN}) Passport passport,
			@PathVariable String routeId) {
		manageRouteUseCase.deleteRoute(routeId);
		return ResponseEntity.noContent().build();
	}

	private RouteResponse toResponse(RouteResult result) {
		return new RouteResponse(
				result.routeId(),
				result.pathPrefix(),
				result.upstreamUrl(),
				result.enabled(),
				result.createdAt(),
				result.updatedAt());
	}
}
