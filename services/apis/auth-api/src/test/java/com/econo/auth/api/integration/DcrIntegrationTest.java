package com.econo.auth.api.integration;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * OIDC Dynamic Client Registration(DCR) E2E 통합 테스트
 *
 * <p>plan: todo.md [통합 테스트 추가 — DCR E2E 흐름 검증] plan: implementation-plan.md DcrIntegrationTest —
 * registrar client 토큰 발급 → POST /connect/register → GET /connect/register?client_id=... → 인가 규칙 검증
 *
 * <p>커버 시나리오:
 *
 * <ol>
 *   <li>registrar client로 client_credentials 토큰 발급 (scope client.create) 성공
 *   <li>그 토큰으로 POST /connect/register → 201 + client_id / registration_access_token 반환
 *   <li>registration_access_token으로 GET /connect/register?client_id=... → 200 (SAS 1.2.1 쿼리 파라미터
 *       방식)
 *   <li>토큰 없이 POST /connect/register → 401
 *   <li>client.create 스코프 없는 토큰으로 POST /connect/register → 401 또는 403
 *   <li>DCR 활성화 후 Discovery 문서에 registration_endpoint 필드 존재 확인
 * </ol>
 *
 * <p>구현 단계 현행 문서 확인: SAS 1.2.1 GET /connect/register 의 정확한 쿼리 파라미터명(client_id vs
 * client_registration_id) 확인 필요. 현재는 RFC 7592 표준의 client_id 쿼리 파라미터 방식으로 작성.
 *
 * <p>현재 상태: AuthorizationServerConfig에 clientRegistrationEndpoint 미활성화, RegisteredClientConfig에
 * registrar client 미추가 → 모든 테스트 Red(컨텍스트 로드 실패 또는 404/401 불일치)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class DcrIntegrationTest {

	// ========================= 테스트 상수 =========================

	private static final String REGISTRAR_CLIENT_ID_VALUE = "registrar-test";
	private static final String REGISTRAR_CLIENT_SECRET_VALUE = "registrar-secret-test-1!";

	// ========================= Testcontainers =========================

	@Container
	static PostgreSQLContainer<?> postgres =
			new PostgreSQLContainer<>("postgres:16")
					.withDatabaseName("auth_dcr_test")
					.withUsername("test")
					.withPassword("test");

	/**
	 * 테스트 환경 프로퍼티 설정
	 *
	 * <p>plan: implementation-plan.md DcrIntegrationTest @DynamicPropertySource — REGISTRAR_CLIENT_ID
	 * / REGISTRAR_CLIENT_SECRET 추가 기존 SAS 공통 환경변수(RSA 키, issuer 등)는 TestRsaKeys 재사용
	 *
	 * @param registry DynamicPropertyRegistry
	 */
	@DynamicPropertySource
	static void configureProperties(DynamicPropertyRegistry registry) {
		// DB
		registry.add("spring.datasource.url", postgres::getJdbcUrl);
		registry.add("spring.datasource.username", postgres::getUsername);
		registry.add("spring.datasource.password", postgres::getPassword);
		registry.add("spring.flyway.enabled", () -> "true");
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");

		// SAS RSA 키 — SasAuthorizationServerIntegrationTest.TestRsaKeys 재사용
		registry.add(
				"RSA_PRIVATE_KEY",
				() -> SasAuthorizationServerIntegrationTest.TestRsaKeys.TEST_PRIVATE_KEY_PEM);
		registry.add(
				"RSA_PUBLIC_KEY",
				() -> SasAuthorizationServerIntegrationTest.TestRsaKeys.TEST_PUBLIC_KEY_PEM);

		// SAS issuer / 프런트 URL / CORS
		registry.add("AUTH_ISSUER_URI", () -> "http://localhost:8080");
		registry.add("auth.frontend-login-url", () -> "http://localhost:3000/login");
		registry.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");

		// 1st-party public client (기존 seed 유지)
		registry.add("FIRST_PARTY_CLIENT_ID", () -> "econo-spa");
		registry.add("FIRST_PARTY_REDIRECT_URI", () -> "http://localhost:3000/callback");

		// plan: RegisteredClientConfig — REGISTRAR_CLIENT_ID / REGISTRAR_CLIENT_SECRET 환경변수 바인딩
		// 이 두 프로퍼티가 없으면 RegisteredClientConfig 빈 생성 시 BeanCreationException(의도된 Red 원인)
		registry.add("REGISTRAR_CLIENT_ID", () -> REGISTRAR_CLIENT_ID_VALUE);
		registry.add("REGISTRAR_CLIENT_SECRET", () -> REGISTRAR_CLIENT_SECRET_VALUE);
	}

	@Autowired private MockMvc mockMvc;

	@Autowired private ObjectMapper objectMapper;

	// ========================= 시나리오 1: registrar 토큰 발급 =========================

	@Nested
	@DisplayName("POST /oauth2/token — registrar client_credentials 토큰 발급")
	class RegistrarTokenIssuanceTest {

		@Test
		@DisplayName("registrar client로 client.create 스코프 토큰 발급에 성공한다")
		void registrarClientCredentialsTokenIssuance() throws Exception {
			// given
			// plan: api-design-plan.md registrar 토큰 발급 흐름 1단계
			// plan: implementation-plan.md RegisteredClientConfig — registrar confidential client seed
			String basicAuth = buildBasicAuth(REGISTRAR_CLIENT_ID_VALUE, REGISTRAR_CLIENT_SECRET_VALUE);

			// when
			MvcResult result =
					mockMvc
							.perform(
									post("/oauth2/token")
											.contentType(MediaType.APPLICATION_FORM_URLENCODED)
											.header("Authorization", "Basic " + basicAuth)
											.param("grant_type", "client_credentials")
											.param("scope", "client.create"))
							.andExpect(status().isOk())
							.andReturn();

			// then
			// plan: api-design-plan.md POST /oauth2/token 응답 — access_token, token_type, scope
			JsonNode tokenResponse = objectMapper.readTree(result.getResponse().getContentAsString());
			assertThat(tokenResponse.has("access_token")).isTrue();
			assertThat(tokenResponse.get("access_token").asText()).isNotBlank();
			assertThat(tokenResponse.get("token_type").asText()).isEqualToIgnoringCase("Bearer");
			// plan: scope에 client.create 포함
			assertThat(tokenResponse.get("scope").asText()).contains("client.create");
		}

		@Test
		@DisplayName("registrar client로 client.read 스코프 포함 토큰 발급에 성공한다")
		void registrarClientCredentialsTokenWithReadScope() throws Exception {
			// given
			// plan: RegisteredClientConfig — scope client.create + client.read 모두 허용
			String basicAuth = buildBasicAuth(REGISTRAR_CLIENT_ID_VALUE, REGISTRAR_CLIENT_SECRET_VALUE);

			// when
			MvcResult result =
					mockMvc
							.perform(
									post("/oauth2/token")
											.contentType(MediaType.APPLICATION_FORM_URLENCODED)
											.header("Authorization", "Basic " + basicAuth)
											.param("grant_type", "client_credentials")
											.param("scope", "client.create client.read"))
							.andExpect(status().isOk())
							.andReturn();

			// then
			JsonNode tokenResponse = objectMapper.readTree(result.getResponse().getContentAsString());
			assertThat(tokenResponse.get("scope").asText()).contains("client.create");
			assertThat(tokenResponse.get("scope").asText()).contains("client.read");
		}

		@Test
		@DisplayName("잘못된 client_secret으로 토큰 요청 시 401 invalid_client 반환")
		void registrarClientWithWrongSecretReturns401() throws Exception {
			// given
			// plan: api-design-plan.md — 에러: invalid_client (client_secret 불일치)
			String wrongAuth = buildBasicAuth(REGISTRAR_CLIENT_ID_VALUE, "wrong-secret");

			// when & then
			MvcResult result =
					mockMvc
							.perform(
									post("/oauth2/token")
											.contentType(MediaType.APPLICATION_FORM_URLENCODED)
											.header("Authorization", "Basic " + wrongAuth)
											.param("grant_type", "client_credentials")
											.param("scope", "client.create"))
							.andExpect(status().isUnauthorized())
							.andReturn();

			// then
			// plan: api-design-plan.md — SAS 표준 에러: {"error":"invalid_client"}
			JsonNode errorResponse = objectMapper.readTree(result.getResponse().getContentAsString());
			assertThat(errorResponse.get("error").asText()).isEqualTo("invalid_client");
		}

		@Test
		@DisplayName("registrar client에 없는 스코프 요청 시 400 invalid_scope 반환")
		void registrarClientWithInvalidScopeReturnsBadRequest() throws Exception {
			// given
			// plan: api-design-plan.md — 에러: invalid_scope (등록되지 않은 scope)
			String basicAuth = buildBasicAuth(REGISTRAR_CLIENT_ID_VALUE, REGISTRAR_CLIENT_SECRET_VALUE);

			// when & then
			MvcResult result =
					mockMvc
							.perform(
									post("/oauth2/token")
											.contentType(MediaType.APPLICATION_FORM_URLENCODED)
											.header("Authorization", "Basic " + basicAuth)
											.param("grant_type", "client_credentials")
											.param("scope", "openid"))
							.andExpect(status().isBadRequest())
							.andReturn();

			JsonNode errorResponse = objectMapper.readTree(result.getResponse().getContentAsString());
			assertThat(errorResponse.get("error").asText()).isEqualTo("invalid_scope");
		}
	}

	// ========================= 시나리오 2: POST /connect/register =========================

	@Nested
	@DisplayName("POST /connect/register — OIDC DCR 신규 client 등록")
	class DcrRegistrationTest {

		@Test
		@DisplayName(
				"client.create 토큰으로 confidential client 등록 시 201과 client_id/registration_access_token을 반환한다")
		void registerConfidentialClientReturns201WithClientIdAndRegistrationToken() throws Exception {
			// given — registrar 토큰 발급
			// plan: api-design-plan.md 2단계 운영 절차 — 1단계 토큰 발급 후 2단계 등록
			String accessToken = issueRegistrarToken("client.create");

			String requestBody =
					"""
					{
						"client_name": "Test Third-Party App",
						"redirect_uris": ["https://thirdparty.example.com/callback"],
						"grant_types": ["authorization_code", "refresh_token"],
						"response_types": ["code"],
						"token_endpoint_auth_method": "client_secret_basic",
						"scope": "openid profile"
					}
					""";

			// when
			// plan: api-design-plan.md POST /connect/register — Bearer access_token + RFC 7591 메타데이터
			MvcResult result =
					mockMvc
							.perform(
									post("/connect/register")
											.contentType(MediaType.APPLICATION_JSON)
											.header("Authorization", "Bearer " + accessToken)
											.content(requestBody))
							.andExpect(status().isCreated())
							.andReturn();

			// then
			// plan: api-design-plan.md POST /connect/register 응답 — client_id, client_secret,
			//   registration_access_token, registration_client_uri + 메타데이터 에코
			JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());

			assertThat(response.has("client_id")).isTrue();
			assertThat(response.get("client_id").asText()).isNotBlank();

			// confidential client이므로 client_secret 반환
			assertThat(response.has("client_secret")).isTrue();
			assertThat(response.get("client_secret").asText()).isNotBlank();

			// plan: registration_access_token은 이후 RFC 7592 조회에 사용
			assertThat(response.has("registration_access_token")).isTrue();
			assertThat(response.get("registration_access_token").asText()).isNotBlank();

			// plan: registration_client_uri 포함
			assertThat(response.has("registration_client_uri")).isTrue();
			assertThat(response.get("registration_client_uri").asText()).isNotBlank();

			// plan: 요청 메타데이터 에코 — redirect_uris, scope, client_name
			assertThat(response.has("redirect_uris")).isTrue();
			assertThat(response.has("scope")).isTrue();
			assertThat(response.get("client_name").asText()).isEqualTo("Test Third-Party App");
		}

		@Test
		@DisplayName("public client 등록 시 201이며 client_secret을 반환하지 않는다")
		void registerPublicClientReturns201WithoutClientSecret() throws Exception {
			// given
			// plan: api-design-plan.md — public client(token_endpoint_auth_method=none)는 client_secret
			// 미반환
			String accessToken = issueRegistrarToken("client.create");

			String requestBody =
					"""
					{
						"client_name": "Test Public SPA",
						"redirect_uris": ["https://spa.example.com/callback"],
						"grant_types": ["authorization_code"],
						"response_types": ["code"],
						"token_endpoint_auth_method": "none",
						"scope": "openid profile"
					}
					""";

			// when
			MvcResult result =
					mockMvc
							.perform(
									post("/connect/register")
											.contentType(MediaType.APPLICATION_JSON)
											.header("Authorization", "Bearer " + accessToken)
											.content(requestBody))
							.andExpect(status().isCreated())
							.andReturn();

			// then
			JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
			assertThat(response.has("client_id")).isTrue();
			// plan: api-design-plan.md — public client는 client_secret 미포함
			assertThat(response.has("client_secret")).isFalse();
		}
	}

	// ========================= 시나리오 3: GET /connect/register?client_id=... =========================

	@Nested
	@DisplayName("GET /connect/register?client_id=... — RFC 7592 client 설정 조회")
	class DcrReadTest {

		@Test
		@DisplayName(
				"registration_access_token으로 GET /connect/register?client_id=... 요청 시 200과 메타데이터를 반환한다")
		void getRegisteredClientReturns200WithMetadata() throws Exception {
			// given — client 등록
			// plan: todo.md 시나리오 4 — registration_access_token으로 GET 조회
			// plan: 호출자 지침 — SAS 1.2.1은 GET /connect/register?client_id=... (쿼리 파라미터) 방식
			String accessToken = issueRegistrarToken("client.create");

			String requestBody =
					"""
					{
						"client_name": "DCR Read Test App",
						"redirect_uris": ["https://readtest.example.com/callback"],
						"grant_types": ["authorization_code"],
						"response_types": ["code"],
						"token_endpoint_auth_method": "client_secret_basic",
						"scope": "openid profile"
					}
					""";

			MvcResult registerResult =
					mockMvc
							.perform(
									post("/connect/register")
											.contentType(MediaType.APPLICATION_JSON)
											.header("Authorization", "Bearer " + accessToken)
											.content(requestBody))
							.andExpect(status().isCreated())
							.andReturn();

			JsonNode registerResponse =
					objectMapper.readTree(registerResult.getResponse().getContentAsString());
			String clientId = registerResponse.get("client_id").asText();
			String registrationAccessToken = registerResponse.get("registration_access_token").asText();

			// when
			// plan: 호출자 지침 — SAS 1.2.1 GET /connect/register?client_id=... (쿼리 파라미터)
			// 구현 단계 현행 문서 확인: SAS 1.2.1 OidcClientRegistrationEndpointFilter의 GET 처리 시
			//   쿼리 파라미터 이름(client_id vs client_registration_id) 현행 소스코드 확인 필요
			MvcResult result =
					mockMvc
							.perform(
									get("/connect/register")
											.param("client_id", clientId)
											.header("Authorization", "Bearer " + registrationAccessToken))
							.andExpect(status().isOk())
							.andReturn();

			// then
			// plan: api-design-plan.md GET /connect/register 응답 — client_id, redirect_uris, scope,
			//   grant_types, registration_client_uri (client_secret 미포함)
			JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
			assertThat(response.get("client_id").asText()).isEqualTo(clientId);
			assertThat(response.has("redirect_uris")).isTrue();
			assertThat(response.has("scope")).isTrue();
			// plan: api-design-plan.md — 조회 응답에 client_secret 미포함 (최초 등록 응답에서만 1회 반환)
			assertThat(response.has("client_secret")).isFalse();
		}
	}

	// ========================= 시나리오 4: 인가 검증 (보안) =========================

	@Nested
	@DisplayName("POST /connect/register — 인가 규칙 검증 (보안)")
	class DcrAuthorizationTest {

		@Test
		@DisplayName("Authorization 헤더 없이 POST /connect/register 요청 시 401을 반환한다")
		void registerWithoutTokenReturns401() throws Exception {
			// given
			// plan: api-design-plan.md POST /connect/register 에러 — 401 invalid_token (Bearer 헤더 없음)
			String requestBody =
					"""
					{
						"client_name": "No Auth App",
						"redirect_uris": ["https://noauth.example.com/callback"],
						"grant_types": ["authorization_code"],
						"response_types": ["code"],
						"token_endpoint_auth_method": "none",
						"scope": "openid"
					}
					""";

			// when & then
			// plan: todo.md 시나리오 3 — 토큰 없이 등록 시도 → 401/403
			mockMvc
					.perform(
							post("/connect/register")
									.contentType(MediaType.APPLICATION_JSON)
									.content(requestBody))
					.andExpect(status().isUnauthorized());
		}

		@Test
		@DisplayName("client.create 스코프 없는 토큰(client.read만)으로 POST /connect/register 시 403을 반환한다")
		void registerWithInsufficientScopeTokenReturns403() throws Exception {
			// given
			// plan: api-design-plan.md POST /connect/register 에러 — 403 insufficient_scope
			// SAS 1.2.1 OidcClientRegistrationAuthenticationProvider가 client.create 스코프 없으면
			// insufficient_scope(403)를 자체 강제한다. authorizationRuleCustomizer 없이도 보장.
			// (#1 보증을 테스트로 잠금 — AuthorizationServerConfig withDefaults() 안전 근거)
			String accessTokenWithReadOnly = issueRegistrarToken("client.read");

			String requestBody =
					"""
					{
						"client_name": "Insufficient Scope App",
						"redirect_uris": ["https://insufficient.example.com/callback"],
						"grant_types": ["authorization_code"],
						"response_types": ["code"],
						"token_endpoint_auth_method": "none",
						"scope": "openid"
					}
					""";

			// when & then
			// plan: api-design-plan.md — 403 insufficient_scope (SAS 1.2.1 기본 동작)
			mockMvc
					.perform(
							post("/connect/register")
									.contentType(MediaType.APPLICATION_JSON)
									.header("Authorization", "Bearer " + accessTokenWithReadOnly)
									.content(requestBody))
					.andExpect(status().isForbidden());
		}

		@Test
		@DisplayName("만료되거나 무효한 Bearer 토큰으로 POST /connect/register 시 401을 반환한다")
		void registerWithInvalidTokenReturns401() throws Exception {
			// given
			// plan: api-design-plan.md POST /connect/register 에러 — 401 invalid_token (만료/무효)
			String invalidToken = "invalid.token.value";

			String requestBody =
					"""
					{
						"client_name": "Invalid Token App",
						"redirect_uris": ["https://invalid-token.example.com/callback"],
						"grant_types": ["authorization_code"],
						"response_types": ["code"],
						"token_endpoint_auth_method": "none",
						"scope": "openid"
					}
					""";

			// when & then
			mockMvc
					.perform(
							post("/connect/register")
									.contentType(MediaType.APPLICATION_JSON)
									.header("Authorization", "Bearer " + invalidToken)
									.content(requestBody))
					.andExpect(status().isUnauthorized());
		}
	}

	// ========================= 시나리오 5 (선택): requireAuthorizationConsent =========================

	@Nested
	@DisplayName("DCR 등록 client requireAuthorizationConsent 기본값 확인")
	class DcrConsentDefaultTest {

		@Test
		@DisplayName(
				"DCR로 등록된 client는 registration_client_uri를 포함한다 (requireAuthorizationConsent 간접 확인)")
		void registeredClientHasRegistrationClientUri() throws Exception {
			// given
			// plan: todo.md 시나리오 5 (선택) — requireAuthorizationConsent(true)로 저장되는지 확인
			// plan: implementation-plan.md 플래그 D — DCR 등록 시 ClientSettings 기본값 주입 hook 확인 필요
			// 직접적인 requireAuthorizationConsent 값 조회는 SAS 내부 구조를 통해서만 가능하므로
			// registration_client_uri 존재 여부로 DCR 설정이 정상 적용됨을 간접 확인한다.
			// 구현 단계 현행 문서 확인: RegisteredClientConverter 커스터마이즈 방법으로 직접 확인 권장
			String accessToken = issueRegistrarToken("client.create");

			String requestBody =
					"""
					{
						"client_name": "Consent Check App",
						"redirect_uris": ["https://consent-check.example.com/callback"],
						"grant_types": ["authorization_code"],
						"response_types": ["code"],
						"token_endpoint_auth_method": "client_secret_basic",
						"scope": "openid profile"
					}
					""";

			// when
			MvcResult result =
					mockMvc
							.perform(
									post("/connect/register")
											.contentType(MediaType.APPLICATION_JSON)
											.header("Authorization", "Bearer " + accessToken)
											.content(requestBody))
							.andExpect(status().isCreated())
							.andReturn();

			// then
			// plan: api-design-plan.md — DCR 등록 client의 기본 설정: requireAuthorizationConsent=true
			JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
			assertThat(response.has("client_id")).isTrue();
			assertThat(response.has("registration_client_uri")).isTrue();
			// registration_client_uri는 {issuer}/connect/register/{clientId} 또는
			// {issuer}/connect/register?client_id={clientId} 형식이어야 한다
			assertThat(response.get("registration_client_uri").asText()).contains("/connect/register");
		}
	}

	// ========================= OIDC Discovery 변경 확인 =========================

	@Nested
	@DisplayName("GET /.well-known/openid-configuration — DCR 활성화 후 Discovery 문서 변경 확인")
	class DcrDiscoveryTest {

		@Test
		@DisplayName("DCR 활성화 후 Discovery 문서에 registration_endpoint 필드가 포함된다")
		void discoveryDocumentContainsRegistrationEndpoint() throws Exception {
			// given
			// plan: api-design-plan.md OIDC Discovery 문서 변경 —
			//   clientRegistrationEndpoint 활성화 시 SAS가 registration_endpoint를 자동 추가
			// plan: todo.md POST /connect/register 엔드포인트 활성화 확인 —
			//   /.well-known/openid-configuration의 registration_endpoint 필드 검증

			// when
			MvcResult result =
					mockMvc
							.perform(get("/.well-known/openid-configuration"))
							.andExpect(status().isOk())
							.andExpect(content().contentType(MediaType.APPLICATION_JSON))
							.andReturn();

			// then
			// plan: api-design-plan.md — registration_endpoint: "https://auth.econo.com/connect/register"
			JsonNode discovery = objectMapper.readTree(result.getResponse().getContentAsString());
			assertThat(discovery.has("registration_endpoint"))
					.as("DCR 활성화 후 OIDC Discovery 문서에 registration_endpoint가 있어야 한다")
					.isTrue();
			assertThat(discovery.get("registration_endpoint").asText()).contains("/connect/register");
		}
	}

	// ========================= 헬퍼 메서드 =========================

	/**
	 * registrar client로 client_credentials access token 발급
	 *
	 * @param scope 요청할 스코프 (예: "client.create", "client.read", "client.create client.read")
	 * @return access token 문자열
	 */
	private String issueRegistrarToken(String scope) throws Exception {
		// plan: api-design-plan.md registrar 토큰 발급 흐름 1단계
		String basicAuth = buildBasicAuth(REGISTRAR_CLIENT_ID_VALUE, REGISTRAR_CLIENT_SECRET_VALUE);

		MvcResult result =
				mockMvc
						.perform(
								post("/oauth2/token")
										.contentType(MediaType.APPLICATION_FORM_URLENCODED)
										.header("Authorization", "Basic " + basicAuth)
										.param("grant_type", "client_credentials")
										.param("scope", scope))
						.andExpect(status().isOk())
						.andReturn();

		JsonNode tokenResponse = objectMapper.readTree(result.getResponse().getContentAsString());
		return tokenResponse.get("access_token").asText();
	}

	/** HTTP Basic Auth 헤더 값 생성 (Base64 인코딩) */
	private String buildBasicAuth(String clientId, String clientSecret) {
		String credentials = clientId + ":" + clientSecret;
		return Base64.getEncoder()
				.encodeToString(credentials.getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}
}
