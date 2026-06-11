package com.econo.auth.api.adapter.in.web;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.econo.auth.api.config.SecurityConfig;
import com.econo.auth.client.application.usecase.ClientRedirectUriService;
import com.econo.auth.client.application.usecase.RegisterOAuthClientService;
import com.econo.auth.client.application.usecase.RegisterOAuthClientService.RegisterOAuthClientCommand;
import com.econo.auth.client.application.usecase.RegisterOAuthClientService.RegisterOAuthClientResult;
import com.econo.auth.client.exception.RedirectUriRequiredException;
import com.econo.common.auth.config.AuthAutoConfiguration;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** AdminClientController 웹 레이어 테스트 */
@WebMvcTest(AdminClientController.class)
@Import({SecurityConfig.class, AuthAutoConfiguration.class})
class AdminClientControllerTest {

	@Autowired private MockMvc mockMvc;

	@MockBean private RegisterOAuthClientService registerOAuthClientService;
	@MockBean private ClientRedirectUriService redirectUriService;

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

	@Nested
	@DisplayName("POST /api/v1/admin/clients — OAuth 클라이언트 등록")
	class RegisterClientTest {

		@Test
		@DisplayName("ADMIN 역할로 등록 성공 시 201과 clientId 반환")
		void register_withAdminPassport_returns201() throws Exception {
			String requestBody =
					"""
					{
						"clientName": "테스트 SPA",
						"redirectUris": ["http://localhost:3000/callback"]
					}
					""";
			given(registerOAuthClientService.register(any(RegisterOAuthClientCommand.class)))
					.willReturn(new RegisterOAuthClientResult("client-uuid-123"));

			mockMvc
					.perform(
							post("/api/v1/admin/clients")
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-User-Passport", ADMIN_PASSPORT)
									.content(requestBody))
					.andExpect(status().isCreated())
					.andExpect(jsonPath("$.clientId").value("client-uuid-123"));
		}

		@Test
		@DisplayName("redirectUris 누락 시 400 반환")
		void register_withoutRedirectUris_returns400() throws Exception {
			given(registerOAuthClientService.register(any(RegisterOAuthClientCommand.class)))
					.willThrow(new RedirectUriRequiredException());

			String requestBody = """
					{
						"clientName": "테스트 SPA"
					}
					""";

			mockMvc
					.perform(
							post("/api/v1/admin/clients")
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-User-Passport", ADMIN_PASSPORT)
									.content(requestBody))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errorCode").value("REDIRECT_URI_REQUIRED"));
		}

		@Test
		@DisplayName("ADMIN 역할 없이 요청 시 403 반환")
		void register_withoutAdminRole_returns403() throws Exception {
			String requestBody =
					"""
					{
						"clientName": "테스트 SPA",
						"redirectUris": ["http://localhost:3000/callback"]
					}
					""";

			mockMvc
					.perform(
							post("/api/v1/admin/clients")
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-User-Passport", USER_PASSPORT)
									.content(requestBody))
					.andExpect(status().isForbidden());
		}

		@Test
		@DisplayName("Passport 헤더 없이 요청 시 401 반환")
		void register_withoutPassport_returns401() throws Exception {
			String requestBody =
					"""
					{
						"clientName": "테스트 SPA",
						"redirectUris": ["http://localhost:3000/callback"]
					}
					""";

			mockMvc
					.perform(
							post("/api/v1/admin/clients")
									.contentType(MediaType.APPLICATION_JSON)
									.content(requestBody))
					.andExpect(status().isUnauthorized());
		}

		@Test
		@DisplayName("clientName이 빈 문자열이면 400 반환")
		void register_withBlankClientName_returns400() throws Exception {
			String requestBody =
					"""
					{
						"clientName": "",
						"redirectUris": ["http://localhost:3000/callback"]
					}
					""";

			mockMvc
					.perform(
							post("/api/v1/admin/clients")
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-User-Passport", ADMIN_PASSPORT)
									.content(requestBody))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
		}

		@Test
		@DisplayName("SUPER_ADMIN 역할로도 등록 가능 (isAdmin이 SUPER_ADMIN 포함)")
		void register_withSuperAdminPassport_returns201() throws Exception {
			String requestBody =
					"""
					{
						"clientName": "테스트 SPA 2",
						"redirectUris": ["http://localhost:3000/callback"]
					}
					""";
			given(registerOAuthClientService.register(any(RegisterOAuthClientCommand.class)))
					.willReturn(new RegisterOAuthClientResult("client-uuid-456"));

			mockMvc
					.perform(
							post("/api/v1/admin/clients")
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-User-Passport", SUPER_ADMIN_PASSPORT)
									.content(requestBody))
					.andExpect(status().isCreated())
					.andExpect(jsonPath("$.clientId").value("client-uuid-456"));
		}
	}

	@Nested
	@DisplayName("GET /api/v1/admin/clients/{clientId} — 클라이언트 조회")
	class GetClientTest {

		@Test
		@DisplayName("ADMIN 역할로 조회 성공 시 200 반환")
		void getClient_withAdmin_returns200() throws Exception {
			given(redirectUriService.findByClientId("client-uuid-123"))
					.willReturn(
							new ClientRedirectUriService.ClientInfo(
									"client-uuid-123",
									"테스트 SPA",
									java.util.Set.of("http://localhost:3000/callback")));

			mockMvc
					.perform(
							get("/api/v1/admin/clients/client-uuid-123")
									.header("X-User-Passport", ADMIN_PASSPORT))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.clientId").value("client-uuid-123"))
					.andExpect(jsonPath("$.clientName").value("테스트 SPA"));
		}

		@Test
		@DisplayName("Passport 없이 요청 시 401 반환")
		void getClient_withoutPassport_returns401() throws Exception {
			mockMvc
					.perform(get("/api/v1/admin/clients/client-uuid-123"))
					.andExpect(status().isUnauthorized());
		}
	}

	@Nested
	@DisplayName("POST /api/v1/admin/clients/{clientId}/redirect-uris — redirectUri 추가")
	class AddRedirectUriTest {

		@Test
		@DisplayName("ADMIN 역할로 추가 성공 시 200 반환")
		void addRedirectUri_withAdmin_returns200() throws Exception {
			given(redirectUriService.addRedirectUri("client-uuid-123", "https://new.example.com/cb"))
					.willReturn(
							java.util.Set.of("http://localhost:3000/callback", "https://new.example.com/cb"));

			mockMvc
					.perform(
							post("/api/v1/admin/clients/client-uuid-123/redirect-uris")
									.header("X-User-Passport", ADMIN_PASSPORT)
									.contentType(MediaType.APPLICATION_JSON)
									.content("{\"uri\":\"https://new.example.com/cb\"}"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.redirectUris").isArray());
		}

		@Test
		@DisplayName("Passport 없이 요청 시 401 반환")
		void addRedirectUri_withoutPassport_returns401() throws Exception {
			mockMvc
					.perform(
							post("/api/v1/admin/clients/client-uuid-123/redirect-uris")
									.contentType(MediaType.APPLICATION_JSON)
									.content("{\"uri\":\"https://new.example.com/cb\"}"))
					.andExpect(status().isUnauthorized());
		}
	}

	@Nested
	@DisplayName("DELETE /api/v1/admin/clients/{clientId}/redirect-uris — redirectUri 제거")
	class RemoveRedirectUriTest {

		@Test
		@DisplayName("ADMIN 역할로 제거 성공 시 200 반환")
		void removeRedirectUri_withAdmin_returns200() throws Exception {
			given(redirectUriService.removeRedirectUri("client-uuid-123", "https://old.example.com/cb"))
					.willReturn(java.util.Set.of("http://localhost:3000/callback"));

			mockMvc
					.perform(
							delete("/api/v1/admin/clients/client-uuid-123/redirect-uris")
									.header("X-User-Passport", ADMIN_PASSPORT)
									.contentType(MediaType.APPLICATION_JSON)
									.content("{\"uri\":\"https://old.example.com/cb\"}"))
					.andExpect(status().isOk());
		}
	}

	@Nested
	@DisplayName("PUT /api/v1/admin/clients/{clientId}/redirect-uris — redirectUri 전체 교체")
	class ReplaceRedirectUrisTest {

		@Test
		@DisplayName("ADMIN 역할로 전체 교체 성공 시 200 반환")
		void replaceRedirectUris_withAdmin_returns200() throws Exception {
			given(redirectUriService.replaceRedirectUris(eq("client-uuid-123"), any()))
					.willReturn(
							java.util.Set.of("https://new1.example.com/cb", "https://new2.example.com/cb"));

			mockMvc
					.perform(
							put("/api/v1/admin/clients/client-uuid-123/redirect-uris")
									.header("X-User-Passport", ADMIN_PASSPORT)
									.contentType(MediaType.APPLICATION_JSON)
									.content(
											"{\"uris\":[\"https://new1.example.com/cb\",\"https://new2.example.com/cb\"]}"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.redirectUris").isArray());
		}
	}
}
