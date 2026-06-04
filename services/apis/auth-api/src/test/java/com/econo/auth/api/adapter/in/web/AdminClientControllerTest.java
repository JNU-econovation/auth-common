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
@Import(SecurityConfig.class)
class AdminClientControllerTest {

	@Autowired private MockMvc mockMvc;

	@MockBean private RegisterOAuthClientService registerOAuthClientService;
	@MockBean private ClientRedirectUriService redirectUriService;

	private static final String ADMIN_PASSPORT =
			Base64.getEncoder()
					.encodeToString(
							"{\"memberId\":1,\"roles\":[\"ADMIN\"],\"issuedAt\":\"2026-01-01T00:00:00\",\"expiresAt\":\"2099-01-01T00:00:00\"}"
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
		@DisplayName("Passport 헤더 없이 요청 시 403 반환")
		void register_withoutPassport_returns403() throws Exception {
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
					.andExpect(status().isForbidden());
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
	}
}
