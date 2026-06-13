package com.econo.auth.api.presentation.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.econo.auth.api.config.security.SecurityConfig;
import com.econo.auth.client.application.usecase.ManageRouteUseCase;
import com.econo.auth.client.application.usecase.ManageRouteUseCase.CreateRouteCommand;
import com.econo.auth.client.application.usecase.ManageRouteUseCase.RouteResult;
import com.econo.auth.client.application.usecase.ManageRouteUseCase.UpdateRouteCommand;
import com.econo.auth.client.exception.RouteNotFoundException;
import com.econo.auth.client.exception.RoutePathConflictException;
import com.econo.auth.client.exception.RouteProtectedException;
import com.econo.auth.client.exception.RouteUpstreamInvalidException;
import com.econo.common.auth.config.AuthAutoConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** AdminRouteController 웹 레이어 테스트 */
@WebMvcTest(AdminRouteController.class)
@Import({SecurityConfig.class, AuthAutoConfiguration.class})
class AdminRouteControllerTest {

	@Autowired private MockMvc mockMvc;

	@MockBean private ManageRouteUseCase manageRouteUseCase;

	private static final String ADMIN_PASSPORT =
			Base64.getEncoder()
					.encodeToString(
							"{\"memberId\":1,\"roles\":[\"ADMIN\"],\"issuedAt\":\"2026-01-01T00:00:00\",\"expiresAt\":\"2099-01-01T00:00:00\"}"
									.getBytes(StandardCharsets.UTF_8));

	private static final String SUPER_ADMIN_PASSPORT =
			Base64.getEncoder()
					.encodeToString(
							"{\"memberId\":3,\"roles\":[\"SUPER_ADMIN\"],\"issuedAt\":\"2026-01-01T00:00:00\",\"expiresAt\":\"2099-01-01T00:00:00\"}"
									.getBytes(StandardCharsets.UTF_8));

	private static final String USER_PASSPORT =
			Base64.getEncoder()
					.encodeToString(
							"{\"memberId\":2,\"roles\":[\"USER\"],\"issuedAt\":\"2026-01-01T00:00:00\",\"expiresAt\":\"2099-01-01T00:00:00\"}"
									.getBytes(StandardCharsets.UTF_8));

	private static RouteResult sampleRouteResult(String routeId) {
		return new RouteResult(
				routeId,
				"/api/v2/new-service",
				"http://new-service:8080",
				true,
				LocalDateTime.of(2026, 6, 13, 10, 0),
				LocalDateTime.of(2026, 6, 13, 10, 0));
	}

	// ──────────────────────────────────────────────────────────
	// POST /api/v1/admin/routes — 라우트 등록
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("POST /api/v1/admin/routes — 라우트 등록")
	class CreateRouteTest {

		@Test
		@DisplayName("ADMIN 역할로 등록 성공 시 201과 routeId 반환")
		void createRoute_withAdminPassport_returns201() throws Exception {
			String requestBody =
					"""
							{
									"pathPrefix": "/api/v2/new-service",
									"upstreamUrl": "http://new-service:8080",
									"enabled": true
							}
							""";
			given(manageRouteUseCase.createRoute(any(CreateRouteCommand.class)))
					.willReturn(sampleRouteResult("route-uuid-1234"));

			mockMvc
					.perform(
							post("/api/v1/admin/routes")
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-User-Passport", ADMIN_PASSPORT)
									.content(requestBody))
					.andExpect(status().isCreated())
					.andExpect(jsonPath("$.routeId").value("route-uuid-1234"))
					.andExpect(jsonPath("$.pathPrefix").value("/api/v2/new-service"))
					.andExpect(jsonPath("$.upstreamUrl").value("http://new-service:8080"))
					.andExpect(jsonPath("$.enabled").value(true));
		}

		@Test
		@DisplayName("SUPER_ADMIN 역할로도 등록 성공 — 201 반환")
		void createRoute_withSuperAdminPassport_returns201() throws Exception {
			String requestBody =
					"""
							{
									"pathPrefix": "/api/v2/super-service",
									"upstreamUrl": "http://super-service:8080",
									"enabled": true
							}
							""";
			given(manageRouteUseCase.createRoute(any(CreateRouteCommand.class)))
					.willReturn(sampleRouteResult("route-uuid-super"));

			mockMvc
					.perform(
							post("/api/v1/admin/routes")
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-User-Passport", SUPER_ADMIN_PASSPORT)
									.content(requestBody))
					.andExpect(status().isCreated())
					.andExpect(jsonPath("$.routeId").isNotEmpty());
		}

		@Test
		@DisplayName("X-User-Passport 헤더 없이 요청 시 401 AUTH_UNAUTHORIZED")
		void createRoute_withoutPassport_returns401() throws Exception {
			String requestBody =
					"""
							{
									"pathPrefix": "/api/v2/new",
									"upstreamUrl": "http://new:8080",
									"enabled": true
							}
							""";

			mockMvc
					.perform(
							post("/api/v1/admin/routes")
									.contentType(MediaType.APPLICATION_JSON)
									.content(requestBody))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.errorCode").value("AUTH_UNAUTHORIZED"));
		}

		@Test
		@DisplayName("ADMIN 미만 역할(USER)로 요청 시 403 FORBIDDEN")
		void createRoute_withUserRole_returns403() throws Exception {
			String requestBody =
					"""
							{
									"pathPrefix": "/api/v2/unauthorized",
									"upstreamUrl": "http://unauthorized:8080",
									"enabled": true
							}
							""";

			mockMvc
					.perform(
							post("/api/v1/admin/routes")
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-User-Passport", USER_PASSPORT)
									.content(requestBody))
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
		}

		@Test
		@DisplayName("pathPrefix 빈 문자열이면 400 VALIDATION_FAILED")
		void createRoute_withBlankPathPrefix_returns400() throws Exception {
			String requestBody =
					"""
							{
									"pathPrefix": "",
									"upstreamUrl": "http://service:8080",
									"enabled": true
							}
							""";

			mockMvc
					.perform(
							post("/api/v1/admin/routes")
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-User-Passport", ADMIN_PASSPORT)
									.content(requestBody))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
		}

		@Test
		@DisplayName("upstreamUrl 빈 문자열이면 400 VALIDATION_FAILED")
		void createRoute_withBlankUpstreamUrl_returns400() throws Exception {
			String requestBody =
					"""
							{
									"pathPrefix": "/api/v2/test",
									"upstreamUrl": "",
									"enabled": true
							}
							""";

			mockMvc
					.perform(
							post("/api/v1/admin/routes")
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-User-Passport", ADMIN_PASSPORT)
									.content(requestBody))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
		}

		@Test
		@DisplayName("enabled null이면 400 VALIDATION_FAILED")
		void createRoute_withNullEnabled_returns400() throws Exception {
			String requestBody =
					"""
							{
									"pathPrefix": "/api/v2/test",
									"upstreamUrl": "http://service:8080"
							}
							""";

			mockMvc
					.perform(
							post("/api/v1/admin/routes")
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-User-Passport", ADMIN_PASSPORT)
									.content(requestBody))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
		}

		@Test
		@DisplayName("SSRF 검증 실패 시 400 ROUTE_UPSTREAM_INVALID")
		void createRoute_withSsrfUpstreamUrl_returns400() throws Exception {
			String requestBody =
					"""
							{
									"pathPrefix": "/api/v2/ssrf",
									"upstreamUrl": "http://127.0.0.1:8080",
									"enabled": true
							}
							""";
			given(manageRouteUseCase.createRoute(any(CreateRouteCommand.class)))
					.willThrow(new RouteUpstreamInvalidException("private IP 차단"));

			mockMvc
					.perform(
							post("/api/v1/admin/routes")
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-User-Passport", ADMIN_PASSPORT)
									.content(requestBody))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errorCode").value("ROUTE_UPSTREAM_INVALID"));
		}

		@Test
		@DisplayName("보호 경로 등록 시도 시 403 ROUTE_PROTECTED")
		void createRoute_withProtectedPath_returns403() throws Exception {
			String requestBody =
					"""
							{
									"pathPrefix": "/api/v1/auth/hijack",
									"upstreamUrl": "http://evil:9090",
									"enabled": true
							}
							""";
			given(manageRouteUseCase.createRoute(any(CreateRouteCommand.class)))
					.willThrow(new RouteProtectedException("/api/v1/auth/hijack"));

			mockMvc
					.perform(
							post("/api/v1/admin/routes")
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-User-Passport", ADMIN_PASSPORT)
									.content(requestBody))
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.errorCode").value("ROUTE_PROTECTED"));
		}

		@Test
		@DisplayName("pathPrefix 중복 시 409 ROUTE_PATH_CONFLICT")
		void createRoute_withDuplicatePath_returns409() throws Exception {
			String requestBody =
					"""
							{
									"pathPrefix": "/api/v2/duplicate",
									"upstreamUrl": "http://service:8080",
									"enabled": true
							}
							""";
			given(manageRouteUseCase.createRoute(any(CreateRouteCommand.class)))
					.willThrow(new RoutePathConflictException("/api/v2/duplicate"));

			mockMvc
					.perform(
							post("/api/v1/admin/routes")
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-User-Passport", ADMIN_PASSPORT)
									.content(requestBody))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.errorCode").value("ROUTE_PATH_CONFLICT"));
		}
	}

	// ──────────────────────────────────────────────────────────
	// GET /api/v1/admin/routes — 전체 목록 조회
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("GET /api/v1/admin/routes — 전체 목록 조회")
	class ListRoutesTest {

		@Test
		@DisplayName("ADMIN 역할로 전체 목록 조회 성공 — 200과 routes 배열 반환")
		void listRoutes_withAdmin_returns200() throws Exception {
			List<RouteResult> routes =
					List.of(
							sampleRouteResult("r1"),
							new RouteResult(
									"r2",
									"/api/v2/other",
									"http://other:8080",
									false,
									LocalDateTime.of(2026, 6, 1, 9, 0),
									LocalDateTime.of(2026, 6, 1, 9, 0)));
			given(manageRouteUseCase.listRoutes()).willReturn(routes);

			mockMvc
					.perform(get("/api/v1/admin/routes").header("X-User-Passport", ADMIN_PASSPORT))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.routes").isArray())
					.andExpect(jsonPath("$.routes.length()").value(2))
					.andExpect(jsonPath("$.routes[0].routeId").value("r1"));
		}

		@Test
		@DisplayName("빈 목록인 경우 200 + routes:[] 반환 (404 아님)")
		void listRoutes_withEmptyList_returns200WithEmptyArray() throws Exception {
			given(manageRouteUseCase.listRoutes()).willReturn(List.of());

			mockMvc
					.perform(get("/api/v1/admin/routes").header("X-User-Passport", ADMIN_PASSPORT))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.routes").isArray())
					.andExpect(jsonPath("$.routes").isEmpty());
		}

		@Test
		@DisplayName("Passport 없이 요청 시 401 AUTH_UNAUTHORIZED")
		void listRoutes_withoutPassport_returns401() throws Exception {
			mockMvc
					.perform(get("/api/v1/admin/routes"))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.errorCode").value("AUTH_UNAUTHORIZED"));
		}

		@Test
		@DisplayName("USER 역할로 요청 시 403 FORBIDDEN")
		void listRoutes_withUserRole_returns403() throws Exception {
			mockMvc
					.perform(get("/api/v1/admin/routes").header("X-User-Passport", USER_PASSPORT))
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
		}
	}

	// ──────────────────────────────────────────────────────────
	// GET /api/v1/admin/routes/{routeId} — 단건 조회
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("GET /api/v1/admin/routes/{routeId} — 단건 조회")
	class GetRouteTest {

		@Test
		@DisplayName("ADMIN 역할로 단건 조회 성공 — 200과 route 정보 반환")
		void getRoute_withAdmin_returns200() throws Exception {
			given(manageRouteUseCase.getRoute("route-uuid-1"))
					.willReturn(sampleRouteResult("route-uuid-1"));

			mockMvc
					.perform(
							get("/api/v1/admin/routes/route-uuid-1").header("X-User-Passport", ADMIN_PASSPORT))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.routeId").value("route-uuid-1"))
					.andExpect(jsonPath("$.pathPrefix").value("/api/v2/new-service"));
		}

		@Test
		@DisplayName("존재하지 않는 routeId 요청 시 404 ROUTE_NOT_FOUND")
		void getRoute_withNonExistentId_returns404() throws Exception {
			given(manageRouteUseCase.getRoute("not-exist"))
					.willThrow(new RouteNotFoundException("not-exist"));

			mockMvc
					.perform(get("/api/v1/admin/routes/not-exist").header("X-User-Passport", ADMIN_PASSPORT))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.errorCode").value("ROUTE_NOT_FOUND"));
		}

		@Test
		@DisplayName("Passport 없이 요청 시 401 AUTH_UNAUTHORIZED")
		void getRoute_withoutPassport_returns401() throws Exception {
			mockMvc.perform(get("/api/v1/admin/routes/some-id")).andExpect(status().isUnauthorized());
		}
	}

	// ──────────────────────────────────────────────────────────
	// PUT /api/v1/admin/routes/{routeId} — 라우트 수정
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("PUT /api/v1/admin/routes/{routeId} — 라우트 수정")
	class UpdateRouteTest {

		@Test
		@DisplayName("ADMIN 역할로 수정 성공 — 200과 수정된 route 반환")
		void updateRoute_withAdmin_returns200() throws Exception {
			String requestBody =
					"""
							{
									"pathPrefix": "/api/v2/renamed-service",
									"upstreamUrl": "http://renamed-service:8080",
									"enabled": false
							}
							""";
			RouteResult updatedResult =
					new RouteResult(
							"route-uuid-update",
							"/api/v2/renamed-service",
							"http://renamed-service:8080",
							false,
							LocalDateTime.of(2026, 6, 13, 10, 0),
							LocalDateTime.of(2026, 6, 13, 10, 30));
			given(manageRouteUseCase.updateRoute(eq("route-uuid-update"), any(UpdateRouteCommand.class)))
					.willReturn(updatedResult);

			mockMvc
					.perform(
							put("/api/v1/admin/routes/route-uuid-update")
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-User-Passport", ADMIN_PASSPORT)
									.content(requestBody))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.routeId").value("route-uuid-update"))
					.andExpect(jsonPath("$.pathPrefix").value("/api/v2/renamed-service"))
					.andExpect(jsonPath("$.enabled").value(false));
		}

		@Test
		@DisplayName("존재하지 않는 routeId 수정 시 404 ROUTE_NOT_FOUND")
		void updateRoute_withNonExistentId_returns404() throws Exception {
			String requestBody =
					"""
							{
									"pathPrefix": "/api/v2/updated",
									"upstreamUrl": "http://updated:8080",
									"enabled": true
							}
							""";
			given(manageRouteUseCase.updateRoute(eq("not-found"), any(UpdateRouteCommand.class)))
					.willThrow(new RouteNotFoundException("not-found"));

			mockMvc
					.perform(
							put("/api/v1/admin/routes/not-found")
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-User-Passport", ADMIN_PASSPORT)
									.content(requestBody))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.errorCode").value("ROUTE_NOT_FOUND"));
		}

		@Test
		@DisplayName("보호 경로로 수정 시도 시 403 ROUTE_PROTECTED")
		void updateRoute_withProtectedPath_returns403() throws Exception {
			String requestBody =
					"""
							{
									"pathPrefix": "/api/v1/auth/protected",
									"upstreamUrl": "http://evil:9090",
									"enabled": true
							}
							""";
			given(manageRouteUseCase.updateRoute(anyString(), any(UpdateRouteCommand.class)))
					.willThrow(new RouteProtectedException("/api/v1/auth/protected"));

			mockMvc
					.perform(
							put("/api/v1/admin/routes/some-route")
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-User-Passport", ADMIN_PASSPORT)
									.content(requestBody))
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.errorCode").value("ROUTE_PROTECTED"));
		}

		@Test
		@DisplayName("pathPrefix 중복 수정 시 409 ROUTE_PATH_CONFLICT")
		void updateRoute_withConflictingPath_returns409() throws Exception {
			String requestBody =
					"""
							{
									"pathPrefix": "/api/v2/conflict",
									"upstreamUrl": "http://service:8080",
									"enabled": true
							}
							""";
			given(manageRouteUseCase.updateRoute(anyString(), any(UpdateRouteCommand.class)))
					.willThrow(new RoutePathConflictException("/api/v2/conflict"));

			mockMvc
					.perform(
							put("/api/v1/admin/routes/some-route")
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-User-Passport", ADMIN_PASSPORT)
									.content(requestBody))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.errorCode").value("ROUTE_PATH_CONFLICT"));
		}

		@Test
		@DisplayName("SSRF 검증 실패 시 400 ROUTE_UPSTREAM_INVALID")
		void updateRoute_withPrivateIp_returns400() throws Exception {
			String requestBody =
					"""
							{
									"pathPrefix": "/api/v2/ssrf-update",
									"upstreamUrl": "http://192.168.1.1:8080",
									"enabled": true
							}
							""";
			given(manageRouteUseCase.updateRoute(anyString(), any(UpdateRouteCommand.class)))
					.willThrow(new RouteUpstreamInvalidException("private IP 차단"));

			mockMvc
					.perform(
							put("/api/v1/admin/routes/some-route")
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-User-Passport", ADMIN_PASSPORT)
									.content(requestBody))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errorCode").value("ROUTE_UPSTREAM_INVALID"));
		}

		@Test
		@DisplayName("Passport 없이 요청 시 401 AUTH_UNAUTHORIZED")
		void updateRoute_withoutPassport_returns401() throws Exception {
			String requestBody =
					"""
							{
									"pathPrefix": "/api/v2/x",
									"upstreamUrl": "http://x:8080",
									"enabled": true
							}
							""";
			mockMvc
					.perform(
							put("/api/v1/admin/routes/some-route")
									.contentType(MediaType.APPLICATION_JSON)
									.content(requestBody))
					.andExpect(status().isUnauthorized());
		}

		@Test
		@DisplayName("USER 역할로 요청 시 403 FORBIDDEN")
		void updateRoute_withUserRole_returns403() throws Exception {
			String requestBody =
					"""
							{
									"pathPrefix": "/api/v2/x",
									"upstreamUrl": "http://x:8080",
									"enabled": true
							}
							""";
			mockMvc
					.perform(
							put("/api/v1/admin/routes/some-route")
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-User-Passport", USER_PASSPORT)
									.content(requestBody))
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
		}
	}

	// ──────────────────────────────────────────────────────────
	// DELETE /api/v1/admin/routes/{routeId} — 라우트 삭제
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("DELETE /api/v1/admin/routes/{routeId} — 라우트 삭제")
	class DeleteRouteTest {

		@Test
		@DisplayName("ADMIN 역할로 삭제 성공 — 204 No Content")
		void deleteRoute_withAdmin_returns204() throws Exception {
			mockMvc
					.perform(
							delete("/api/v1/admin/routes/route-uuid-del")
									.header("X-User-Passport", ADMIN_PASSPORT))
					.andExpect(status().isNoContent());
		}

		@Test
		@DisplayName("존재하지 않는 routeId 삭제 시 404 ROUTE_NOT_FOUND")
		void deleteRoute_withNonExistentId_returns404() throws Exception {
			willThrow(new RouteNotFoundException("not-found"))
					.given(manageRouteUseCase)
					.deleteRoute("not-found");

			mockMvc
					.perform(
							delete("/api/v1/admin/routes/not-found").header("X-User-Passport", ADMIN_PASSPORT))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.errorCode").value("ROUTE_NOT_FOUND"));
		}

		@Test
		@DisplayName("보호 경로 삭제 시도 시 403 ROUTE_PROTECTED")
		void deleteRoute_withProtectedRoute_returns403() throws Exception {
			willThrow(new RouteProtectedException("/api/v1/auth/login"))
					.given(manageRouteUseCase)
					.deleteRoute("protected-route-id");

			mockMvc
					.perform(
							delete("/api/v1/admin/routes/protected-route-id")
									.header("X-User-Passport", ADMIN_PASSPORT))
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.errorCode").value("ROUTE_PROTECTED"));
		}

		@Test
		@DisplayName("Passport 없이 삭제 요청 시 401 AUTH_UNAUTHORIZED")
		void deleteRoute_withoutPassport_returns401() throws Exception {
			mockMvc.perform(delete("/api/v1/admin/routes/some-id")).andExpect(status().isUnauthorized());
		}

		@Test
		@DisplayName("USER 역할로 삭제 요청 시 403 FORBIDDEN")
		void deleteRoute_withUserRole_returns403() throws Exception {
			mockMvc
					.perform(delete("/api/v1/admin/routes/some-id").header("X-User-Passport", USER_PASSPORT))
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
		}
	}
}
