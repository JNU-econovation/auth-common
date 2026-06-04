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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** AdminClientController 웹 레이어 테스트 */
@WebMvcTest(AdminClientController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "AUTH_INTERNAL_API_KEY=valid-internal-key")
class AdminClientControllerTest {

	@Autowired private MockMvc mockMvc;

	@MockBean private RegisterOAuthClientService registerOAuthClientService;
	@MockBean private ClientRedirectUriService redirectUriService;

	// ──────────────────────────────────────────────────────────
	// POST /api/v1/admin/clients
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("POST /api/v1/admin/clients — OAuth 클라이언트 등록")
	class RegisterClientTest {

		@Test
		@DisplayName("등록 성공 시 201과 clientId 반환")
		@WithMockUser(roles = "ADMIN")
		void register_returns201WithClientId() throws Exception {
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
									.header("X-Internal-Api-Key", "valid-internal-key")
									.content(requestBody))
					.andExpect(status().isCreated())
					.andExpect(jsonPath("$.clientId").value("client-uuid-123"))
					.andExpect(jsonPath("$.clientSecret").doesNotExist());
		}

		@Test
		@DisplayName("redirectUris 누락 시 400 반환")
		@WithMockUser(roles = "ADMIN")
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
									.header("X-Internal-Api-Key", "valid-internal-key")
									.content(requestBody))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errorCode").value("REDIRECT_URI_REQUIRED"));
		}

		@Test
		@DisplayName("Internal API Key 없이 요청 시 401 반환")
		void register_withoutApiKey_returns401() throws Exception {
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
		@WithMockUser(roles = "ADMIN")
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
									.header("X-Internal-Api-Key", "valid-internal-key")
									.content(requestBody))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
		}
	}
}
