package com.econo.auth.api.adapter.in.web;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.econo.auth.api.application.ClientRedirectUriService;
import com.econo.auth.api.application.usecase.RegisterOAuthClientService;
import com.econo.auth.api.application.usecase.RegisterOAuthClientService.RegisterOAuthClientCommand;
import com.econo.auth.api.application.usecase.RegisterOAuthClientService.RegisterOAuthClientResult;
import com.econo.auth.api.config.SecurityConfig;
import com.econo.auth.api.exception.RedirectUriRequiredException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.test.web.servlet.MockMvc;

/**
 * AdminClientController 웹 레이어 테스트 — OAuth 클라이언트 등록 API
 *
 * <p>plan: POST /api/v1/admin/clients — OAuth 클라이언트 등록 (public) plan: GET /api/v1/admin/routes —
 * Gateway용 라우트 목록 (public) plan: GET /api/v1/admin/clients/{clientId} — Basic Auth 인증 plan:
 * POST|DELETE|PUT /api/v1/admin/clients/{clientId}/redirect-uris — Basic Auth 인증
 */
@WebMvcTest(AdminClientController.class)
@Import(SecurityConfig.class)
class AdminClientControllerTest {

	@Autowired private MockMvc mockMvc;

	@MockBean private RegisterOAuthClientService registerOAuthClientService;
	@MockBean private ClientRedirectUriService redirectUriService;
	@MockBean private RegisteredClientRepository registeredClientRepository;
	@MockBean private PasswordEncoder passwordEncoder;

	/** "Basic " + Base64(id + ":" + secret) 형태의 Authorization 헤더 값 생성 */
	private String basicAuthHeader(String id, String secret) {
		return "Basic "
				+ Base64.getEncoder().encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8));
	}

	// ──────────────────────────────────────────────────────────
	// POST /api/v1/admin/clients
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("POST /api/v1/admin/clients — OAuth 클라이언트 등록")
	class RegisterClientTest {

		@Test
		@DisplayName("authorization_code 타입으로 클라이언트 등록 성공 시 201과 clientId 반환")
		void registerAuthorizationCodeClient_returns201WithClientId() throws Exception {
			// given
			String requestBody =
					"""
					{
						"grantType": "authorization_code",
						"clientName": "테스트 SPA",
						"redirectUris": ["http://localhost:3000/callback"]
					}
					""";
			given(registerOAuthClientService.register(any(RegisterOAuthClientCommand.class)))
					.willReturn(new RegisterOAuthClientResult("client-uuid-123", null, null));

			// when & then
			mockMvc
					.perform(
							post("/api/v1/admin/clients")
									.contentType(MediaType.APPLICATION_JSON)
									.content(requestBody))
					.andExpect(status().isCreated())
					.andExpect(jsonPath("$.clientId").value("client-uuid-123"))
					.andExpect(jsonPath("$.clientSecret").doesNotExist());
		}

		@Test
		@DisplayName("client_credentials 타입으로 등록 시 201과 clientId + clientSecret 반환")
		void registerClientCredentials_returns201WithClientIdAndSecret() throws Exception {
			// given
			String requestBody =
					"""
					{
						"grantType": "client_credentials",
						"clientName": "배치 서비스"
					}
					""";
			given(registerOAuthClientService.register(any(RegisterOAuthClientCommand.class)))
					.willReturn(new RegisterOAuthClientResult("client-uuid-456", "raw-secret-xyz", null));

			// when & then
			mockMvc
					.perform(
							post("/api/v1/admin/clients")
									.contentType(MediaType.APPLICATION_JSON)
									.content(requestBody))
					.andExpect(status().isCreated())
					.andExpect(jsonPath("$.clientId").value("client-uuid-456"))
					.andExpect(jsonPath("$.clientSecret").value("raw-secret-xyz"));
		}

		@Test
		@DisplayName("authorization_code 타입에서 redirectUris 누락 시 400 반환")
		void registerAuthorizationCode_withoutRedirectUris_returns400() throws Exception {
			// given — redirectUri 검증은 서비스에서 처리하므로 mock이 예외를 던지도록 설정
			given(registerOAuthClientService.register(any(RegisterOAuthClientCommand.class)))
					.willThrow(new RedirectUriRequiredException());

			String requestBody =
					"""
					{
						"grantType": "authorization_code",
						"clientName": "테스트 SPA"
					}
					""";

			// when & then
			mockMvc
					.perform(
							post("/api/v1/admin/clients")
									.contentType(MediaType.APPLICATION_JSON)
									.content(requestBody))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errorCode").value("REDIRECT_URI_REQUIRED"));
		}

		@Test
		@Disabled("refactor-client-registration: SAS 포트 통합 후 재활성")
		@DisplayName("grantType 생략 시 201과 clientId + clientSecret 반환")
		void registerWithoutGrantType_returns201WithClientIdAndSecret() throws Exception {
			// given — grantType 키 자체 없음
			String requestBody = """
					{
						"clientName": "app-b"
					}
					""";
			given(registerOAuthClientService.register(any(RegisterOAuthClientCommand.class)))
					.willReturn(new RegisterOAuthClientResult("cid", "secret", null));

			// when & then
			mockMvc
					.perform(
							post("/api/v1/admin/clients")
									.contentType(MediaType.APPLICATION_JSON)
									.content(requestBody))
					.andExpect(status().isCreated())
					.andExpect(jsonPath("$.clientId").value("cid"))
					.andExpect(jsonPath("$.clientSecret").value("secret"));
		}

		@Test
		@DisplayName("지원하지 않는 grantType 입력 시 400 반환")
		void registerWithUnsupportedGrantType_returns400() throws Exception {
			// given
			String requestBody =
					"""
					{
						"grantType": "password",
						"clientName": "구형 클라이언트"
					}
					""";

			// when & then
			mockMvc
					.perform(
							post("/api/v1/admin/clients")
									.contentType(MediaType.APPLICATION_JSON)
									.content(requestBody))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_GRANT_TYPE"));
		}

		@Test
		@DisplayName("upstreamUrl + pathPrefix 지정 시 등록 성공 — 라우트 ID 포함 응답")
		void registerWithUpstreamUrl_returnsRouteId() throws Exception {
			// given
			String requestBody =
					"""
					{
						"grantType": "client_credentials",
						"clientName": "이커머스 서비스",
						"upstreamUrl": "http://ecommerce-service:8080",
						"pathPrefix": "/api/shop"
					}
					""";
			given(registerOAuthClientService.register(any(RegisterOAuthClientCommand.class)))
					.willReturn(
							new RegisterOAuthClientResult("client-uuid-789", "raw-secret-abc", "route-uuid-001"));

			// when & then
			mockMvc
					.perform(
							post("/api/v1/admin/clients")
									.contentType(MediaType.APPLICATION_JSON)
									.content(requestBody))
					.andExpect(status().isCreated())
					.andExpect(jsonPath("$.clientId").value("client-uuid-789"))
					.andExpect(jsonPath("$.clientSecret").value("raw-secret-abc"))
					.andExpect(jsonPath("$.routeId").value("route-uuid-001"));
		}

		@Test
		@DisplayName("clientName이 빈 문자열이면 400 VALIDATION_FAILED 반환")
		void registerWithBlankClientName_returns400() throws Exception {
			// given
			String requestBody =
					"""
					{
						"grantType": "client_credentials",
						"clientName": ""
					}
					""";

			// when & then
			mockMvc
					.perform(
							post("/api/v1/admin/clients")
									.contentType(MediaType.APPLICATION_JSON)
									.content(requestBody))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
		}

		@Test
		@DisplayName("X-Internal-Api-Key 헤더 포함 요청도 무시하고 201 반환 (하위 호환)")
		void registerWithLegacyApiKeyHeader_returns201() throws Exception {
			// given
			String requestBody =
					"""
					{"grantType":"client_credentials","clientName":"하위호환테스트"}
					""";
			given(registerOAuthClientService.register(any()))
					.willReturn(new RegisterOAuthClientResult("cid", "secret", null));

			// when & then — 구 헤더 포함해도 정상 처리
			mockMvc
					.perform(
							post("/api/v1/admin/clients")
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-Internal-Api-Key", "any-legacy-value")
									.content(requestBody))
					.andExpect(status().isCreated());
		}
	}

	// ──────────────────────────────────────────────────────────
	// GET /api/v1/admin/routes
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("GET /api/v1/admin/routes — Gateway 라우트 목록 조회")
	class GetRoutesTest {

		@Test
		@DisplayName("인증 헤더 없이 요청해도 200과 라우트 목록 반환 (public endpoint)")
		void getRoutes_withoutAuth_returns200() throws Exception {
			// given — 인증 헤더 없음 (public endpoint)
			// when & then
			mockMvc
					.perform(get("/api/v1/admin/routes"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.routes").isArray());
		}

		@Test
		@DisplayName("X-Internal-Api-Key 헤더 포함 요청도 무시하고 200 반환 (하위 호환)")
		void getRoutesWithLegacyApiKeyHeader_returns200() throws Exception {
			// given — 구 헤더 포함 (무시됨)
			// when & then
			mockMvc
					.perform(get("/api/v1/admin/routes").header("X-Internal-Api-Key", "any-legacy-value"))
					.andExpect(status().isOk());
		}
	}

	// ──────────────────────────────────────────────────────────
	// GET /api/v1/admin/clients/{clientId} — Basic Auth
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("GET /api/v1/admin/clients/{clientId} — Basic Auth 인증")
	class GetClientBasicAuthTest {

		private static final String CLIENT_ID = "test-client-id";
		private static final String RAW_SECRET = "raw-secret-xyz";
		private static final String BCRYPT_HASH = "{bcrypt}$2a$12$hashedvalue";

		@Test
		@DisplayName("올바른 Basic Auth 자격증명으로 요청 시 200 반환")
		void getClient_withValidBasicAuth_returns200() throws Exception {
			// given
			RegisteredClient mockClient = mock(RegisteredClient.class);
			given(mockClient.getClientSecret()).willReturn(BCRYPT_HASH);
			given(registeredClientRepository.findByClientId(CLIENT_ID)).willReturn(mockClient);
			given(passwordEncoder.matches(RAW_SECRET, "$2a$12$hashedvalue")).willReturn(true);

			org.springframework.security.oauth2.server.authorization.client.RegisteredClient
					serviceClient = mock(RegisteredClient.class);
			given(serviceClient.getClientId()).willReturn(CLIENT_ID);
			given(serviceClient.getClientName()).willReturn("테스트 클라이언트");
			given(serviceClient.getRedirectUris())
					.willReturn(Set.of("https://app.econovation.kr/callback"));
			given(redirectUriService.findByClientId(CLIENT_ID)).willReturn(serviceClient);

			// when & then
			mockMvc
					.perform(
							get("/api/v1/admin/clients/" + CLIENT_ID)
									.header("Authorization", basicAuthHeader(CLIENT_ID, RAW_SECRET)))
					.andExpect(status().isOk());
		}

		@Test
		@DisplayName("잘못된 clientSecret으로 요청 시 401 INVALID_CLIENT_CREDENTIALS 반환")
		void getClient_withWrongSecret_returns401() throws Exception {
			// given
			RegisteredClient mockClient = mock(RegisteredClient.class);
			given(mockClient.getClientSecret()).willReturn(BCRYPT_HASH);
			given(registeredClientRepository.findByClientId(CLIENT_ID)).willReturn(mockClient);
			given(passwordEncoder.matches("wrong-secret", "$2a$12$hashedvalue")).willReturn(false);

			// when & then
			mockMvc
					.perform(
							get("/api/v1/admin/clients/" + CLIENT_ID)
									.header("Authorization", basicAuthHeader(CLIENT_ID, "wrong-secret")))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.errorCode").value("INVALID_CLIENT_CREDENTIALS"))
					.andExpect(header().string("WWW-Authenticate", "Basic realm=\"admin\""));
		}

		@Test
		@DisplayName("path clientId와 Basic Auth clientId 불일치 시 403 FORBIDDEN_CLIENT_MISMATCH 반환")
		void getClient_withMismatchedClientId_returns403() throws Exception {
			// given — 올바른 자격증명이나 path {clientId}와 Basic Auth clientId가 다름
			String otherClientId = "other-client-id";
			RegisteredClient mockClient = mock(RegisteredClient.class);
			given(mockClient.getClientSecret()).willReturn(BCRYPT_HASH);
			given(registeredClientRepository.findByClientId(otherClientId)).willReturn(mockClient);
			given(passwordEncoder.matches(eq(RAW_SECRET), anyString())).willReturn(true);

			// when & then
			mockMvc
					.perform(
							get("/api/v1/admin/clients/" + CLIENT_ID)
									.header("Authorization", basicAuthHeader(otherClientId, RAW_SECRET)))
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.errorCode").value("FORBIDDEN_CLIENT_MISMATCH"));
		}

		@Test
		@DisplayName("Authorization 헤더 누락 시 401 INVALID_CLIENT_CREDENTIALS 반환")
		void getClient_withoutAuthorizationHeader_returns401() throws Exception {
			// given — Authorization 헤더 없음
			// when & then
			mockMvc
					.perform(get("/api/v1/admin/clients/" + CLIENT_ID))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.errorCode").value("INVALID_CLIENT_CREDENTIALS"))
					.andExpect(header().string("WWW-Authenticate", "Basic realm=\"admin\""));
		}
	}

	// ──────────────────────────────────────────────────────────
	// POST /api/v1/admin/clients/{clientId}/redirect-uris — Basic Auth
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("POST /api/v1/admin/clients/{clientId}/redirect-uris — Basic Auth 인증")
	class AddRedirectUriBasicAuthTest {

		private static final String CLIENT_ID = "test-client-id";
		private static final String RAW_SECRET = "raw-secret-xyz";
		private static final String BCRYPT_HASH = "{bcrypt}$2a$12$hashedvalue";
		private static final String REQUEST_BODY = "{\"uri\":\"https://dev.econovation.kr/callback\"}";

		@Test
		@DisplayName("올바른 Basic Auth 자격증명으로 redirectUri 추가 시 200 반환")
		void addRedirectUri_withValidBasicAuth_returns200() throws Exception {
			// given
			RegisteredClient mockClient = mock(RegisteredClient.class);
			given(mockClient.getClientSecret()).willReturn(BCRYPT_HASH);
			given(registeredClientRepository.findByClientId(CLIENT_ID)).willReturn(mockClient);
			given(passwordEncoder.matches(RAW_SECRET, "$2a$12$hashedvalue")).willReturn(true);
			given(redirectUriService.addRedirectUri(eq(CLIENT_ID), anyString()))
					.willReturn(Set.of("https://dev.econovation.kr/callback"));

			// when & then
			mockMvc
					.perform(
							post("/api/v1/admin/clients/" + CLIENT_ID + "/redirect-uris")
									.contentType(MediaType.APPLICATION_JSON)
									.header("Authorization", basicAuthHeader(CLIENT_ID, RAW_SECRET))
									.content(REQUEST_BODY))
					.andExpect(status().isOk());
		}

		@Test
		@DisplayName("잘못된 clientSecret으로 요청 시 401 INVALID_CLIENT_CREDENTIALS 반환")
		void addRedirectUri_withWrongSecret_returns401() throws Exception {
			// given
			RegisteredClient mockClient = mock(RegisteredClient.class);
			given(mockClient.getClientSecret()).willReturn(BCRYPT_HASH);
			given(registeredClientRepository.findByClientId(CLIENT_ID)).willReturn(mockClient);
			given(passwordEncoder.matches("wrong-secret", "$2a$12$hashedvalue")).willReturn(false);

			// when & then
			mockMvc
					.perform(
							post("/api/v1/admin/clients/" + CLIENT_ID + "/redirect-uris")
									.contentType(MediaType.APPLICATION_JSON)
									.header("Authorization", basicAuthHeader(CLIENT_ID, "wrong-secret"))
									.content(REQUEST_BODY))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.errorCode").value("INVALID_CLIENT_CREDENTIALS"))
					.andExpect(header().string("WWW-Authenticate", "Basic realm=\"admin\""));
		}

		@Test
		@DisplayName("path clientId와 Basic Auth clientId 불일치 시 403 FORBIDDEN_CLIENT_MISMATCH 반환")
		void addRedirectUri_withMismatchedClientId_returns403() throws Exception {
			// given
			String otherClientId = "other-client-id";
			RegisteredClient mockClient = mock(RegisteredClient.class);
			given(mockClient.getClientSecret()).willReturn(BCRYPT_HASH);
			given(registeredClientRepository.findByClientId(otherClientId)).willReturn(mockClient);
			given(passwordEncoder.matches(eq(RAW_SECRET), anyString())).willReturn(true);

			// when & then
			mockMvc
					.perform(
							post("/api/v1/admin/clients/" + CLIENT_ID + "/redirect-uris")
									.contentType(MediaType.APPLICATION_JSON)
									.header("Authorization", basicAuthHeader(otherClientId, RAW_SECRET))
									.content(REQUEST_BODY))
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.errorCode").value("FORBIDDEN_CLIENT_MISMATCH"));
		}

		@Test
		@DisplayName("Authorization 헤더 누락 시 401 INVALID_CLIENT_CREDENTIALS 반환")
		void addRedirectUri_withoutAuthorizationHeader_returns401() throws Exception {
			// given — Authorization 헤더 없음
			// when & then
			mockMvc
					.perform(
							post("/api/v1/admin/clients/" + CLIENT_ID + "/redirect-uris")
									.contentType(MediaType.APPLICATION_JSON)
									.content(REQUEST_BODY))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.errorCode").value("INVALID_CLIENT_CREDENTIALS"))
					.andExpect(header().string("WWW-Authenticate", "Basic realm=\"admin\""));
		}
	}

	// ──────────────────────────────────────────────────────────
	// DELETE /api/v1/admin/clients/{clientId}/redirect-uris — Basic Auth
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("DELETE /api/v1/admin/clients/{clientId}/redirect-uris — Basic Auth 인증")
	class RemoveRedirectUriBasicAuthTest {

		private static final String CLIENT_ID = "test-client-id";
		private static final String RAW_SECRET = "raw-secret-xyz";
		private static final String BCRYPT_HASH = "{bcrypt}$2a$12$hashedvalue";
		private static final String REQUEST_BODY = "{\"uri\":\"https://dev.econovation.kr/callback\"}";

		@Test
		@DisplayName("올바른 Basic Auth 자격증명으로 redirectUri 제거 시 200 반환")
		void removeRedirectUri_withValidBasicAuth_returns200() throws Exception {
			// given
			RegisteredClient mockClient = mock(RegisteredClient.class);
			given(mockClient.getClientSecret()).willReturn(BCRYPT_HASH);
			given(registeredClientRepository.findByClientId(CLIENT_ID)).willReturn(mockClient);
			given(passwordEncoder.matches(RAW_SECRET, "$2a$12$hashedvalue")).willReturn(true);
			given(redirectUriService.removeRedirectUri(eq(CLIENT_ID), anyString()))
					.willReturn(Set.of("https://app.econovation.kr/callback"));

			// when & then
			mockMvc
					.perform(
							delete("/api/v1/admin/clients/" + CLIENT_ID + "/redirect-uris")
									.contentType(MediaType.APPLICATION_JSON)
									.header("Authorization", basicAuthHeader(CLIENT_ID, RAW_SECRET))
									.content(REQUEST_BODY))
					.andExpect(status().isOk());
		}

		@Test
		@DisplayName("잘못된 clientSecret으로 요청 시 401 INVALID_CLIENT_CREDENTIALS 반환")
		void removeRedirectUri_withWrongSecret_returns401() throws Exception {
			// given
			RegisteredClient mockClient = mock(RegisteredClient.class);
			given(mockClient.getClientSecret()).willReturn(BCRYPT_HASH);
			given(registeredClientRepository.findByClientId(CLIENT_ID)).willReturn(mockClient);
			given(passwordEncoder.matches("wrong-secret", "$2a$12$hashedvalue")).willReturn(false);

			// when & then
			mockMvc
					.perform(
							delete("/api/v1/admin/clients/" + CLIENT_ID + "/redirect-uris")
									.contentType(MediaType.APPLICATION_JSON)
									.header("Authorization", basicAuthHeader(CLIENT_ID, "wrong-secret"))
									.content(REQUEST_BODY))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.errorCode").value("INVALID_CLIENT_CREDENTIALS"))
					.andExpect(header().string("WWW-Authenticate", "Basic realm=\"admin\""));
		}

		@Test
		@DisplayName("path clientId와 Basic Auth clientId 불일치 시 403 FORBIDDEN_CLIENT_MISMATCH 반환")
		void removeRedirectUri_withMismatchedClientId_returns403() throws Exception {
			// given
			String otherClientId = "other-client-id";
			RegisteredClient mockClient = mock(RegisteredClient.class);
			given(mockClient.getClientSecret()).willReturn(BCRYPT_HASH);
			given(registeredClientRepository.findByClientId(otherClientId)).willReturn(mockClient);
			given(passwordEncoder.matches(eq(RAW_SECRET), anyString())).willReturn(true);

			// when & then
			mockMvc
					.perform(
							delete("/api/v1/admin/clients/" + CLIENT_ID + "/redirect-uris")
									.contentType(MediaType.APPLICATION_JSON)
									.header("Authorization", basicAuthHeader(otherClientId, RAW_SECRET))
									.content(REQUEST_BODY))
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.errorCode").value("FORBIDDEN_CLIENT_MISMATCH"));
		}

		@Test
		@DisplayName("Authorization 헤더 누락 시 401 INVALID_CLIENT_CREDENTIALS 반환")
		void removeRedirectUri_withoutAuthorizationHeader_returns401() throws Exception {
			// given — Authorization 헤더 없음
			// when & then
			mockMvc
					.perform(
							delete("/api/v1/admin/clients/" + CLIENT_ID + "/redirect-uris")
									.contentType(MediaType.APPLICATION_JSON)
									.content(REQUEST_BODY))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.errorCode").value("INVALID_CLIENT_CREDENTIALS"))
					.andExpect(header().string("WWW-Authenticate", "Basic realm=\"admin\""));
		}
	}

	// ──────────────────────────────────────────────────────────
	// PUT /api/v1/admin/clients/{clientId}/redirect-uris — Basic Auth
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("PUT /api/v1/admin/clients/{clientId}/redirect-uris — Basic Auth 인증")
	class ReplaceRedirectUrisBasicAuthTest {

		private static final String CLIENT_ID = "test-client-id";
		private static final String RAW_SECRET = "raw-secret-xyz";
		private static final String BCRYPT_HASH = "{bcrypt}$2a$12$hashedvalue";
		private static final String REQUEST_BODY =
				"{\"uris\":[\"https://app.econovation.kr/callback\"]}";

		@Test
		@DisplayName("올바른 Basic Auth 자격증명으로 redirectUris 전체 교체 시 200 반환")
		void replaceRedirectUris_withValidBasicAuth_returns200() throws Exception {
			// given
			RegisteredClient mockClient = mock(RegisteredClient.class);
			given(mockClient.getClientSecret()).willReturn(BCRYPT_HASH);
			given(registeredClientRepository.findByClientId(CLIENT_ID)).willReturn(mockClient);
			given(passwordEncoder.matches(RAW_SECRET, "$2a$12$hashedvalue")).willReturn(true);
			given(redirectUriService.replaceRedirectUris(eq(CLIENT_ID), any()))
					.willReturn(Set.of("https://app.econovation.kr/callback"));

			// when & then
			mockMvc
					.perform(
							put("/api/v1/admin/clients/" + CLIENT_ID + "/redirect-uris")
									.contentType(MediaType.APPLICATION_JSON)
									.header("Authorization", basicAuthHeader(CLIENT_ID, RAW_SECRET))
									.content(REQUEST_BODY))
					.andExpect(status().isOk());
		}

		@Test
		@DisplayName("잘못된 clientSecret으로 요청 시 401 INVALID_CLIENT_CREDENTIALS 반환")
		void replaceRedirectUris_withWrongSecret_returns401() throws Exception {
			// given
			RegisteredClient mockClient = mock(RegisteredClient.class);
			given(mockClient.getClientSecret()).willReturn(BCRYPT_HASH);
			given(registeredClientRepository.findByClientId(CLIENT_ID)).willReturn(mockClient);
			given(passwordEncoder.matches("wrong-secret", "$2a$12$hashedvalue")).willReturn(false);

			// when & then
			mockMvc
					.perform(
							put("/api/v1/admin/clients/" + CLIENT_ID + "/redirect-uris")
									.contentType(MediaType.APPLICATION_JSON)
									.header("Authorization", basicAuthHeader(CLIENT_ID, "wrong-secret"))
									.content(REQUEST_BODY))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.errorCode").value("INVALID_CLIENT_CREDENTIALS"))
					.andExpect(header().string("WWW-Authenticate", "Basic realm=\"admin\""));
		}

		@Test
		@DisplayName("path clientId와 Basic Auth clientId 불일치 시 403 FORBIDDEN_CLIENT_MISMATCH 반환")
		void replaceRedirectUris_withMismatchedClientId_returns403() throws Exception {
			// given
			String otherClientId = "other-client-id";
			RegisteredClient mockClient = mock(RegisteredClient.class);
			given(mockClient.getClientSecret()).willReturn(BCRYPT_HASH);
			given(registeredClientRepository.findByClientId(otherClientId)).willReturn(mockClient);
			given(passwordEncoder.matches(eq(RAW_SECRET), anyString())).willReturn(true);

			// when & then
			mockMvc
					.perform(
							put("/api/v1/admin/clients/" + CLIENT_ID + "/redirect-uris")
									.contentType(MediaType.APPLICATION_JSON)
									.header("Authorization", basicAuthHeader(otherClientId, RAW_SECRET))
									.content(REQUEST_BODY))
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.errorCode").value("FORBIDDEN_CLIENT_MISMATCH"));
		}

		@Test
		@DisplayName("Authorization 헤더 누락 시 401 INVALID_CLIENT_CREDENTIALS 반환")
		void replaceRedirectUris_withoutAuthorizationHeader_returns401() throws Exception {
			// given — Authorization 헤더 없음
			// when & then
			mockMvc
					.perform(
							put("/api/v1/admin/clients/" + CLIENT_ID + "/redirect-uris")
									.contentType(MediaType.APPLICATION_JSON)
									.content(REQUEST_BODY))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.errorCode").value("INVALID_CLIENT_CREDENTIALS"))
					.andExpect(header().string("WWW-Authenticate", "Basic realm=\"admin\""));
		}
	}
}
