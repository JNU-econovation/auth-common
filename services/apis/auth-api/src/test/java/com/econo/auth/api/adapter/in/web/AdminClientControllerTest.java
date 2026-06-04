package com.econo.auth.api.adapter.in.web;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.econo.auth.api.application.usecase.RegisterOAuthClientService;
import com.econo.auth.api.application.usecase.RegisterOAuthClientService.RegisterOAuthClientCommand;
import com.econo.auth.api.application.usecase.RegisterOAuthClientService.RegisterOAuthClientResult;
import com.econo.auth.api.config.SecurityConfig;
import com.econo.auth.api.exception.RedirectUriRequiredException;
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

/** AdminClientController 웹 레이어 테스트 — OAuth 클라이언트 등록 API */
@WebMvcTest(AdminClientController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "AUTH_INTERNAL_API_KEY=valid-internal-key")
class AdminClientControllerTest {

	@Autowired private MockMvc mockMvc;

	@MockBean private RegisterOAuthClientService registerOAuthClientService;
	@MockBean private com.econo.auth.api.application.ClientRedirectUriService redirectUriService;

	// ──────────────────────────────────────────────────────────
	// POST /api/v1/admin/clients
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("POST /api/v1/admin/clients — OAuth 클라이언트 등록")
	class RegisterClientTest {

		@Test
		@DisplayName("authorization_code 타입으로 클라이언트 등록 성공 시 201과 clientId 반환")
		@WithMockUser(roles = "ADMIN")
		void registerAuthorizationCodeClient_returns201WithClientId() throws Exception {
			String requestBody =
					"""
					{
						"grantType": "authorization_code",
						"clientName": "테스트 SPA",
						"redirectUris": ["http://localhost:3000/callback"]
					}
					""";
			given(registerOAuthClientService.register(any(RegisterOAuthClientCommand.class)))
					.willReturn(new RegisterOAuthClientResult("client-uuid-123", null));

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
		@DisplayName("client_credentials 타입으로 등록 시 201과 clientId + clientSecret 반환")
		@WithMockUser(roles = "ADMIN")
		void registerClientCredentials_returns201WithClientIdAndSecret() throws Exception {
			String requestBody =
					"""
					{
						"grantType": "client_credentials",
						"clientName": "배치 서비스"
					}
					""";
			given(registerOAuthClientService.register(any(RegisterOAuthClientCommand.class)))
					.willReturn(new RegisterOAuthClientResult("client-uuid-456", "raw-secret-xyz"));

			mockMvc
					.perform(
							post("/api/v1/admin/clients")
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-Internal-Api-Key", "valid-internal-key")
									.content(requestBody))
					.andExpect(status().isCreated())
					.andExpect(jsonPath("$.clientId").value("client-uuid-456"))
					.andExpect(jsonPath("$.clientSecret").value("raw-secret-xyz"));
		}

		@Test
		@DisplayName("authorization_code 타입에서 redirectUris 누락 시 400 반환")
		@WithMockUser(roles = "ADMIN")
		void registerAuthorizationCode_withoutRedirectUris_returns400() throws Exception {
			given(registerOAuthClientService.register(any(RegisterOAuthClientCommand.class)))
					.willThrow(new RedirectUriRequiredException());

			String requestBody =
					"""
					{
						"grantType": "authorization_code",
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
		@DisplayName("지원하지 않는 grantType 입력 시 400 반환")
		@WithMockUser(roles = "ADMIN")
		void registerWithUnsupportedGrantType_returns400() throws Exception {
			String requestBody =
					"""
					{
						"grantType": "password",
						"clientName": "구형 클라이언트"
					}
					""";

			mockMvc
					.perform(
							post("/api/v1/admin/clients")
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-Internal-Api-Key", "valid-internal-key")
									.content(requestBody))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_GRANT_TYPE"));
		}

		@Test
		@DisplayName("Internal API Key 없이 요청 시 401 반환")
		void registerWithoutApiKey_returns401() throws Exception {
			String requestBody =
					"""
					{
						"grantType": "authorization_code",
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
		@DisplayName("clientName이 빈 문자열이면 400 VALIDATION_FAILED 반환")
		@WithMockUser(roles = "ADMIN")
		void registerWithBlankClientName_returns400() throws Exception {
			String requestBody =
					"""
					{
						"grantType": "client_credentials",
						"clientName": ""
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
