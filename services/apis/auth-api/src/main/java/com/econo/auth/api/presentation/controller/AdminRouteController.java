package com.econo.auth.api.presentation.controller;

import com.econo.auth.api.presentation.docs.AdminRouteApiDocs;
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
 * <p>ADMIN 또는 SUPER_ADMIN 역할이 필요하다. Gateway가 JWT를 검증하고 X-User-Passport 헤더를 주입한다. Swagger 문서는 {@link
 * AdminRouteApiDocs}에 분리한다.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminRouteController implements AdminRouteApiDocs {

	private final ManageRouteUseCase manageRouteUseCase;

	@Override
	@PostMapping("/routes")
	public ResponseEntity<RouteResponse> createRoute(
			@PassportAuth(requiredRoles = {Roles.ADMIN, Roles.SUPER_ADMIN}) Passport passport,
			@Valid @RequestBody CreateRouteRequest request) {
		CreateRouteCommand command =
				new CreateRouteCommand(request.pathPrefix(), request.upstreamUrl(), request.enabled());
		RouteResult result = manageRouteUseCase.createRoute(command);
		return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(result));
	}

	@Override
	@GetMapping("/routes")
	public ResponseEntity<RouteListResponse> listRoutes(
			@PassportAuth(requiredRoles = {Roles.ADMIN, Roles.SUPER_ADMIN}) Passport passport) {
		List<RouteResult> results = manageRouteUseCase.listRoutes();
		List<RouteResponse> responses = results.stream().map(this::toResponse).toList();
		return ResponseEntity.ok(new RouteListResponse(responses));
	}

	@Override
	@GetMapping("/routes/{routeId}")
	public ResponseEntity<RouteResponse> getRoute(
			@PassportAuth(requiredRoles = {Roles.ADMIN, Roles.SUPER_ADMIN}) Passport passport,
			@PathVariable String routeId) {
		RouteResult result = manageRouteUseCase.getRoute(routeId);
		return ResponseEntity.ok(toResponse(result));
	}

	@Override
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

	@Override
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
