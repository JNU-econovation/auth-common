package com.econo.auth.api.integration;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * SAS 인가 서버 통합 테스트 — auth-api
 *
 * <p>plan: todo.md [커스텀 JSON 로그인 통합 테스트] — POST /api/v1/auth/login 세션 쿠키 확인, 잘못된 자격증명 401 plan:
 * todo.md [SAS 토큰 발급 통합 테스트] — Authorization Code + PKCE E2E, 커스텀 클레임 확인 plan: api-design-plan.md —
 * Access Token 클레임: memberId, loginId, name, generation, status, roles
 *
 * <p>구현 단계 현행 문서 확인: SAS 1.x JdbcRegisteredClientRepository, JwtEncodingContext API 구현 단계 현행 문서 확인:
 * spring-session-jdbc V3 스키마 마이그레이션 완료 후 실제 세션 쿠키(SESSION) 검증 가능 구현 단계 현행 문서 확인:
 * JsonLoginAuthenticationFilter 구현 완료 후 로그인 플로우 검증 가능
 *
 * <p>현재 상태: 구현체(AuthorizationServerConfig, RegisteredClientConfig, RsaKeyConfig 등)가 없으므로 모든 테스트는
 * Red(컴파일 에러 또는 애플리케이션 컨텍스트 로드 실패)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class SasAuthorizationServerIntegrationTest {

	@Container
	static PostgreSQLContainer<?> postgres =
			new PostgreSQLContainer<>("postgres:16")
					.withDatabaseName("auth_test")
					.withUsername("test")
					.withPassword("test");

	@DynamicPropertySource
	static void configureProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", postgres::getJdbcUrl);
		registry.add("spring.datasource.username", postgres::getUsername);
		registry.add("spring.datasource.password", postgres::getPassword);
		registry.add("spring.flyway.enabled", () -> "true");
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
		// plan: implementation-plan.md — RSA_PRIVATE_KEY/RSA_PUBLIC_KEY PEM 환경변수 방식
		// 테스트용 RSA 키 주입 (구현 단계 현행 문서 확인: PEM 문자열 형식 확인)
		registry.add("RSA_PRIVATE_KEY", () -> TestRsaKeys.TEST_PRIVATE_KEY_PEM);
		registry.add("RSA_PUBLIC_KEY", () -> TestRsaKeys.TEST_PUBLIC_KEY_PEM);
		// plan: implementation-plan.md — AUTH_ISSUER_URI = Gateway 공개 URL
		registry.add("AUTH_ISSUER_URI", () -> "http://localhost:8080");
		// plan: api-design-plan.md — auth.frontend-login-url 환경변수
		registry.add("auth.frontend-login-url", () -> "http://localhost:3000/login");
		// plan: api-design-plan.md — CORS_ALLOWED_ORIGINS
		registry.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
		// plan: RegisteredClientConfig — FIRST_PARTY_CLIENT_ID, FIRST_PARTY_REDIRECT_URI
		registry.add("FIRST_PARTY_CLIENT_ID", () -> "econo-spa");
		registry.add("FIRST_PARTY_REDIRECT_URI", () -> "http://localhost:3000/callback");
		registry.add("AUTH_INTERNAL_API_KEY", () -> "test-internal-key");
	}

	@Autowired private MockMvc mockMvc;

	@Autowired private ObjectMapper objectMapper;

	@Nested
	@DisplayName("POST /api/v1/auth/login — 커스텀 JSON 로그인 통합 테스트")
	class JsonLoginIntegrationTest {

		@Test
		@DisplayName("가입 후 JSON 로그인 성공 시 SESSION 쿠키가 Set-Cookie로 내려온다")
		void signupThenLoginReceivesSessionCookie() throws Exception {
			// given — 회원 가입
			String signupBody =
					"""
					{
						"name": "홍길동",
						"loginId": "sas_test_user01",
						"password": "Econo1234!",
						"generation": 32,
						"status": "AM"
					}
					""";
			mockMvc
					.perform(
							post("/api/v1/auth/signup")
									.contentType(MediaType.APPLICATION_JSON)
									.content(signupBody))
					.andExpect(status().isCreated());

			String loginBody =
					"""
					{
						"loginId": "sas_test_user01",
						"password": "Econo1234!"
					}
					""";

			// when
			// plan: JsonLoginAuthenticationFilter — POST /api/v1/auth/login 처리, 세션 수립
			MvcResult loginResult =
					mockMvc
							.perform(
									post("/api/v1/auth/login")
											.contentType(MediaType.APPLICATION_JSON)
											.content(loginBody))
							.andExpect(status().isOk())
							.andReturn();

			// then
			// plan: api-design-plan.md — Set-Cookie: SESSION=<session-id>; HttpOnly; SameSite=None;
			// Secure; Path=/
			String setCookieHeader = loginResult.getResponse().getHeader("Set-Cookie");
			assertThat(setCookieHeader).isNotNull();
			// plan: 세션 쿠키 이름은 SESSION (Spring Session 기본값)
			assertThat(setCookieHeader).startsWith("SESSION=");
			assertThat(setCookieHeader).containsIgnoringCase("HttpOnly");
		}

		@Test
		@DisplayName("로그인 성공 시 응답 바디는 비어있다 (토큰 미발급)")
		void loginSuccessResponseBodyIsEmpty() throws Exception {
			// given — 사전 가입
			String signupBody =
					"""
					{
						"name": "홍길동",
						"loginId": "sas_test_empty_body",
						"password": "Econo1234!",
						"generation": 32,
						"status": "AM"
					}
					""";
			mockMvc
					.perform(
							post("/api/v1/auth/signup")
									.contentType(MediaType.APPLICATION_JSON)
									.content(signupBody))
					.andExpect(status().isCreated());

			String loginBody =
					"""
					{
						"loginId": "sas_test_empty_body",
						"password": "Econo1234!"
					}
					""";

			// when
			MvcResult result =
					mockMvc
							.perform(
									post("/api/v1/auth/login")
											.contentType(MediaType.APPLICATION_JSON)
											.content(loginBody))
							.andExpect(status().isOk())
							.andReturn();

			// then
			// plan: api-design-plan.md — 바디 없음 (빈 200). Access/ID/Refresh 토큰은 이 엔드포인트에서 발급하지 않음
			assertThat(result.getResponse().getContentAsString()).isEmpty();
		}

		@Test
		@DisplayName("잘못된 자격증명으로 로그인 시 401 INVALID_CREDENTIALS 반환")
		void loginWithInvalidCredentialsReturns401() throws Exception {
			// given
			String loginBody =
					"""
					{
						"loginId": "nonexistent_user",
						"password": "WrongPassword1!"
					}
					""";

			// when & then
			// plan: api-design-plan.md — 401 INVALID_CREDENTIALS
			// plan: JsonLoginAuthenticationFilter.unsuccessfulAuthentication — 기존 에러 구조 재사용
			MvcResult result =
					mockMvc
							.perform(
									post("/api/v1/auth/login")
											.contentType(MediaType.APPLICATION_JSON)
											.content(loginBody))
							.andExpect(status().isUnauthorized())
							.andReturn();

			JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
			assertThat(response.get("errorCode").asText()).isEqualTo("INVALID_CREDENTIALS");
			assertThat(response.get("message").asText()).isEqualTo("아이디 또는 비밀번호가 올바르지 않습니다.");
		}

		@Test
		@DisplayName("비밀번호가 틀리면 401 INVALID_CREDENTIALS 반환")
		void loginWithWrongPasswordReturns401() throws Exception {
			// given — 사전 가입
			String signupBody =
					"""
					{
						"name": "홍길동",
						"loginId": "sas_wrong_pw_user",
						"password": "Econo1234!",
						"generation": 32,
						"status": "AM"
					}
					""";
			mockMvc
					.perform(
							post("/api/v1/auth/signup")
									.contentType(MediaType.APPLICATION_JSON)
									.content(signupBody))
					.andExpect(status().isCreated());

			String loginBody =
					"""
					{
						"loginId": "sas_wrong_pw_user",
						"password": "WrongPassword1!"
					}
					""";

			// when & then
			mockMvc
					.perform(
							post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(loginBody))
					.andExpect(status().isUnauthorized());
		}
	}

	@Nested
	@DisplayName("GET /oauth2/authorize — 미인증 진입점 테스트")
	class AuthorizeUnauthenticatedTest {

		@Test
		@DisplayName("미인증 상태로 GET /oauth2/authorize 진입 시 외부 프런트 로그인 URL로 302 리다이렉트")
		void unauthenticatedAuthorizeRedirectsToFrontendLogin() throws Exception {
			// given — 세션 없는 상태
			// plan: api-design-plan.md authorize 진입점 정책 — authenticationEntryPoint → 302 프런트 로그인 URL
			MvcResult result =
					mockMvc
							.perform(
									get("/oauth2/authorize")
											.param("response_type", "code")
											.param("client_id", "econo-spa")
											.param("redirect_uri", "http://localhost:3000/callback")
											.param("scope", "openid profile")
											.param("code_challenge", "some-challenge")
											.param("code_challenge_method", "S256"))
							.andExpect(status().is3xxRedirection())
							.andReturn();

			// then
			// plan: api-design-plan.md — Location: https://app.econo.com/login?redirect_uri=...
			String location = result.getResponse().getHeader("Location");
			assertThat(location).isNotNull();
			// plan: auth.frontend-login-url = http://localhost:3000/login (테스트 설정)
			assertThat(location).startsWith("http://localhost:3000/login");
			assertThat(location).contains("redirect_uri");
		}

		@Test
		@DisplayName("세션 수립 후 GET /oauth2/authorize 요청이 authorization code로 리다이렉트된다")
		void authenticatedAuthorizeRedirectsWithCode() throws Exception {
			// given — 회원 가입 + 로그인으로 세션 수립
			String signupBody =
					"""
					{
						"name": "홍길동",
						"loginId": "sas_authorize_user",
						"password": "Econo1234!",
						"generation": 32,
						"status": "AM"
					}
					""";
			mockMvc
					.perform(
							post("/api/v1/auth/signup")
									.contentType(MediaType.APPLICATION_JSON)
									.content(signupBody))
					.andExpect(status().isCreated());

			String loginBody =
					"""
					{
						"loginId": "sas_authorize_user",
						"password": "Econo1234!"
					}
					""";

			// plan: JsonLoginAuthenticationFilter — 로그인 성공 시 세션 수립
			MvcResult loginResult =
					mockMvc
							.perform(
									post("/api/v1/auth/login")
											.contentType(MediaType.APPLICATION_JSON)
											.content(loginBody))
							.andExpect(status().isOk())
							.andReturn();

			// 세션 쿠키 추출
			MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession();

			// when — 세션 있는 상태로 /oauth2/authorize 요청
			// plan: GET /oauth2/authorize — SAS 자동 처리, 302 code redirect
			MvcResult authorizeResult =
					mockMvc
							.perform(
									get("/oauth2/authorize")
											.session(session)
											.param("response_type", "code")
											.param("client_id", "econo-spa")
											.param("redirect_uri", "http://localhost:3000/callback")
											.param("scope", "openid profile")
											.param("code_challenge", buildCodeChallenge("test-verifier-123456789012345"))
											.param("code_challenge_method", "S256"))
							.andExpect(status().is3xxRedirection())
							.andReturn();

			// then
			String location = authorizeResult.getResponse().getHeader("Location");
			assertThat(location).isNotNull();
			// plan: 302 Found + Location: {redirect_uri}?code=<authorization_code>&state=...
			assertThat(location).startsWith("http://localhost:3000/callback");
			assertThat(location).contains("code=");
		}
	}

	@Nested
	@DisplayName("Authorization Code + PKCE E2E — 토큰 발급 및 커스텀 클레임 테스트")
	class TokenIssuanceE2ETest {

		@Test
		@DisplayName("전체 PKCE 플로우 완료 후 Access Token에 Passport 커스텀 클레임이 포함된다")
		void pkceFlowAccessTokenContainsPassportClaims() throws Exception {
			// given — 회원 가입
			String signupBody =
					"""
					{
						"name": "김종민",
						"loginId": "sas_pkce_user",
						"password": "Econo1234!",
						"generation": 11,
						"status": "AM"
					}
					""";
			mockMvc
					.perform(
							post("/api/v1/auth/signup")
									.contentType(MediaType.APPLICATION_JSON)
									.content(signupBody))
					.andExpect(status().isCreated());

			// 로그인 → 세션 수립
			String loginBody =
					"""
					{
						"loginId": "sas_pkce_user",
						"password": "Econo1234!"
					}
					""";
			MvcResult loginResult =
					mockMvc
							.perform(
									post("/api/v1/auth/login")
											.contentType(MediaType.APPLICATION_JSON)
											.content(loginBody))
							.andExpect(status().isOk())
							.andReturn();
			MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession();

			// PKCE code_verifier/code_challenge 생성
			String codeVerifier = "test-pkce-verifier-12345678901234567890";
			String codeChallenge = buildCodeChallenge(codeVerifier);

			// /oauth2/authorize → authorization code 취득
			// plan: GET /oauth2/authorize — SAS 자동 처리, Authorization Code 생성
			MvcResult authorizeResult =
					mockMvc
							.perform(
									get("/oauth2/authorize")
											.session(session)
											.param("response_type", "code")
											.param("client_id", "econo-spa")
											.param("redirect_uri", "http://localhost:3000/callback")
											.param("scope", "openid profile")
											.param("code_challenge", codeChallenge)
											.param("code_challenge_method", "S256"))
							.andExpect(status().is3xxRedirection())
							.andReturn();

			String location = authorizeResult.getResponse().getHeader("Location");
			String authCode = extractCodeFromLocation(location);
			assertThat(authCode).isNotBlank();

			// when — POST /oauth2/token — Authorization Code → Access Token 교환
			// plan: POST /oauth2/token — SAS 자동 처리, PassportTokenCustomizer 커스텀 클레임 주입
			MvcResult tokenResult =
					mockMvc
							.perform(
									post("/oauth2/token")
											.contentType(MediaType.APPLICATION_FORM_URLENCODED)
											.param("grant_type", "authorization_code")
											.param("code", authCode)
											.param("redirect_uri", "http://localhost:3000/callback")
											.param("client_id", "econo-spa")
											.param("code_verifier", codeVerifier))
							.andExpect(status().isOk())
							.andReturn();

			// then
			JsonNode tokenResponse =
					objectMapper.readTree(tokenResult.getResponse().getContentAsString());
			String accessToken = tokenResponse.get("access_token").asText();
			String idToken =
					tokenResponse.has("id_token") ? tokenResponse.get("id_token").asText() : null;

			assertThat(accessToken).isNotBlank();

			// Access Token JWT 파싱 후 커스텀 클레임 확인
			// plan: api-design-plan.md Access Token 클레임: memberId, loginId, name, generation, status,
			// roles
			JsonNode accessTokenClaims = parseJwtPayload(accessToken);
			assertThat(accessTokenClaims.has("memberId")).isTrue();
			assertThat(accessTokenClaims.get("loginId").asText()).isEqualTo("sas_pkce_user");
			assertThat(accessTokenClaims.get("name").asText()).isEqualTo("김종민");
			assertThat(accessTokenClaims.get("generation").asInt()).isEqualTo(11);
			assertThat(accessTokenClaims.get("status").asText()).isEqualTo("AM");
			assertThat(accessTokenClaims.has("roles")).isTrue();

			// ID Token에도 동일 커스텀 클레임 포함
			// plan: api-design-plan.md ID Token 클레임 — PassportTokenCustomizer 주입
			if (idToken != null) {
				JsonNode idTokenClaims = parseJwtPayload(idToken);
				assertThat(idTokenClaims.has("memberId")).isTrue();
				assertThat(idTokenClaims.get("loginId").asText()).isEqualTo("sas_pkce_user");
			}
		}

		@Test
		@DisplayName("Access Token의 iss 클레임이 AUTH_ISSUER_URI와 일치한다")
		void accessTokenIssuerMatchesAuthIssuerUri() throws Exception {
			// given — 회원 가입 + 로그인 + authorize + token 교환
			// (단순화: 가입된 사용자가 없으면 실패하므로 setup 재사용)
			String signupBody =
					"""
					{
						"name": "이슈어",
						"loginId": "sas_issuer_user",
						"password": "Econo1234!",
						"generation": 5,
						"status": "AM"
					}
					""";
			mockMvc
					.perform(
							post("/api/v1/auth/signup")
									.contentType(MediaType.APPLICATION_JSON)
									.content(signupBody))
					.andExpect(status().isCreated());

			MvcResult loginResult =
					mockMvc
							.perform(
									post("/api/v1/auth/login")
											.contentType(MediaType.APPLICATION_JSON)
											.content(
													"""
													{"loginId":"sas_issuer_user","password":"Econo1234!"}
													"""))
							.andExpect(status().isOk())
							.andReturn();
			MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession();

			String codeVerifier = "issuer-test-verifier-123456789012345";
			String codeChallenge = buildCodeChallenge(codeVerifier);

			MvcResult authorizeResult =
					mockMvc
							.perform(
									get("/oauth2/authorize")
											.session(session)
											.param("response_type", "code")
											.param("client_id", "econo-spa")
											.param("redirect_uri", "http://localhost:3000/callback")
											.param("scope", "openid profile")
											.param("code_challenge", codeChallenge)
											.param("code_challenge_method", "S256"))
							.andExpect(status().is3xxRedirection())
							.andReturn();

			String authCode =
					extractCodeFromLocation(authorizeResult.getResponse().getHeader("Location"));

			MvcResult tokenResult =
					mockMvc
							.perform(
									post("/oauth2/token")
											.contentType(MediaType.APPLICATION_FORM_URLENCODED)
											.param("grant_type", "authorization_code")
											.param("code", authCode)
											.param("redirect_uri", "http://localhost:3000/callback")
											.param("client_id", "econo-spa")
											.param("code_verifier", codeVerifier))
							.andExpect(status().isOk())
							.andReturn();

			// when
			JsonNode tokenResponse =
					objectMapper.readTree(tokenResult.getResponse().getContentAsString());
			String accessToken = tokenResponse.get("access_token").asText();
			JsonNode claims = parseJwtPayload(accessToken);

			// then
			// plan: api-design-plan.md — iss = AUTH_ISSUER_URI (= "http://localhost:8080" in tests)
			// plan: AuthorizationServerConfig — AuthorizationServerSettings.issuer = AUTH_ISSUER_URI
			assertThat(claims.get("iss").asText()).isEqualTo("http://localhost:8080");
		}
	}

	@Nested
	@DisplayName("GET /oauth2/jwks — JWKS 공개키 엔드포인트 테스트")
	class JwksEndpointTest {

		@Test
		@DisplayName("/oauth2/jwks가 RSA 공개키를 JWK Set으로 반환한다")
		void jwksEndpointReturnsRsaPublicKey() throws Exception {
			// when
			// plan: GET /oauth2/jwks — SAS 자동 노출; RSA 공개키 JWK Set
			MvcResult result =
					mockMvc
							.perform(get("/oauth2/jwks"))
							.andExpect(status().isOk())
							.andExpect(content().contentType(MediaType.APPLICATION_JSON))
							.andReturn();

			// then
			JsonNode jwkSet = objectMapper.readTree(result.getResponse().getContentAsString());
			assertThat(jwkSet.has("keys")).isTrue();
			assertThat(jwkSet.get("keys").isArray()).isTrue();
			assertThat(jwkSet.get("keys").size()).isGreaterThan(0);

			JsonNode firstKey = jwkSet.get("keys").get(0);
			// plan: api-design-plan.md — kty: RSA, use: sig, alg: RS256
			assertThat(firstKey.get("kty").asText()).isEqualTo("RSA");
			assertThat(firstKey.get("use").asText()).isEqualTo("sig");
			assertThat(firstKey.get("alg").asText()).isEqualTo("RS256");
			assertThat(firstKey.has("n")).isTrue();
			assertThat(firstKey.has("e")).isTrue();
			// 비공개 키(d)는 포함되지 않아야 함
			assertThat(firstKey.has("d")).isFalse();
		}
	}

	@Nested
	@DisplayName("GET /.well-known/openid-configuration — OIDC Discovery 테스트")
	class OidcDiscoveryTest {

		@Test
		@DisplayName("/.well-known/openid-configuration의 issuer가 AUTH_ISSUER_URI와 일치한다")
		void oidcDiscoveryIssuerMatchesAuthIssuerUri() throws Exception {
			// when
			// plan: GET /.well-known/openid-configuration — SAS 자동 노출; issuer-uri 설정값 매핑 통합 테스트
			MvcResult result =
					mockMvc
							.perform(get("/.well-known/openid-configuration"))
							.andExpect(status().isOk())
							.andExpect(content().contentType(MediaType.APPLICATION_JSON))
							.andReturn();

			// then
			JsonNode discovery = objectMapper.readTree(result.getResponse().getContentAsString());
			// plan: api-design-plan.md — issuer = AUTH_ISSUER_URI (테스트 설정: "http://localhost:8080")
			assertThat(discovery.get("issuer").asText()).isEqualTo("http://localhost:8080");
			assertThat(discovery.has("authorization_endpoint")).isTrue();
			assertThat(discovery.has("token_endpoint")).isTrue();
			assertThat(discovery.has("jwks_uri")).isTrue();
		}

		@Test
		@DisplayName("Discovery 문서에 code_challenge_methods_supported에 S256이 포함된다")
		void discoveryDocumentIncludesPkceS256() throws Exception {
			// when
			MvcResult result =
					mockMvc
							.perform(get("/.well-known/openid-configuration"))
							.andExpect(status().isOk())
							.andReturn();

			// then
			JsonNode discovery = objectMapper.readTree(result.getResponse().getContentAsString());
			// plan: api-design-plan.md — code_challenge_methods_supported: ["S256"]
			assertThat(discovery.has("code_challenge_methods_supported")).isTrue();
			boolean hasS256 = false;
			for (JsonNode method : discovery.get("code_challenge_methods_supported")) {
				if ("S256".equals(method.asText())) {
					hasS256 = true;
					break;
				}
			}
			assertThat(hasS256).isTrue();
		}
	}

	@Nested
	@DisplayName("POST /api/v1/auth/logout — 세션 무효화 테스트")
	class LogoutIntegrationTest {

		@Test
		@DisplayName("로그인 후 로그아웃 시 SESSION 쿠키가 Max-Age=0으로 만료된다")
		void logoutAfterLoginExpiratesSessionCookie() throws Exception {
			// given — 가입 + 로그인
			String signupBody =
					"""
					{
						"name": "홍길동",
						"loginId": "sas_logout_user",
						"password": "Econo1234!",
						"generation": 32,
						"status": "AM"
					}
					""";
			mockMvc
					.perform(
							post("/api/v1/auth/signup")
									.contentType(MediaType.APPLICATION_JSON)
									.content(signupBody))
					.andExpect(status().isCreated());

			MvcResult loginResult =
					mockMvc
							.perform(
									post("/api/v1/auth/login")
											.contentType(MediaType.APPLICATION_JSON)
											.content(
													"""
													{"loginId":"sas_logout_user","password":"Econo1234!"}
													"""))
							.andExpect(status().isOk())
							.andReturn();
			MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession();

			// when
			// plan: POST /api/v1/auth/logout — HttpSession.invalidate() + SESSION 쿠키 만료
			MvcResult logoutResult =
					mockMvc
							.perform(post("/api/v1/auth/logout").session(session))
							.andExpect(status().isOk())
							.andReturn();

			// then
			// plan: api-design-plan.md — Set-Cookie: SESSION=; HttpOnly; SameSite=None; Secure; Path=/;
			// Max-Age=0
			String setCookieHeader = logoutResult.getResponse().getHeader("Set-Cookie");
			assertThat(setCookieHeader).isNotNull();
			assertThat(setCookieHeader).startsWith("SESSION=");
			assertThat(setCookieHeader).containsIgnoringCase("Max-Age=0");
		}

		@Test
		@DisplayName("세션 없이 로그아웃해도 200 반환 (멱등)")
		void logoutWithoutSessionIsIdempotent() throws Exception {
			// plan: api-design-plan.md — 세션 쿠키가 없는 상태로 호출해도 200 OK 반환 (멱등 처리)
			mockMvc.perform(post("/api/v1/auth/logout")).andExpect(status().isOk());
		}
	}

	// ========================= 헬퍼 메서드 =========================

	/** PKCE S256 code_challenge 생성 */
	private String buildCodeChallenge(String codeVerifier) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		byte[] hash = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
		return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
	}

	/** Location 헤더에서 code 파라미터 추출 */
	private String extractCodeFromLocation(String location) {
		Pattern pattern = Pattern.compile("[?&]code=([^&]+)");
		Matcher matcher = pattern.matcher(location);
		if (matcher.find()) {
			return matcher.group(1);
		}
		return "";
	}

	/** JWT 페이로드 파싱 (서명 검증 없이) */
	private JsonNode parseJwtPayload(String jwt) throws Exception {
		String[] parts = jwt.split("\\.");
		if (parts.length < 2) {
			throw new IllegalArgumentException("Invalid JWT format");
		}
		byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
		return objectMapper.readTree(payloadBytes);
	}

	/**
	 * 테스트용 RSA 키 PEM 상수
	 *
	 * <p>구현 단계 현행 문서 확인: PEM 형식 + RsaKeyConfig 파싱 방식 일치 여부 확인 필요 실제 구현 단계에서 테스트용 PEM 키를 keytool 또는
	 * openssl로 생성하여 교체
	 */
	static class TestRsaKeys {

		/** 테스트용 2048-bit RSA Private Key PEM (PKCS#8, BEGIN PRIVATE KEY) */
		static final String TEST_PRIVATE_KEY_PEM =
				"-----BEGIN PRIVATE KEY-----\n"
						+ "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQCslgR/+1e1H+A3\n"
						+ "pMD7+2FWpMQt9whRD+XG30BMi19Ec8Mb2drE/SH5U+emSrMwQaVdSgRmHcRXwERD\n"
						+ "G2cpMsR+ZsoxDGwobt+e/X4NV9I1/9VIuOopt87ez4E2l/Oahcie2WyEh/h19U+p\n"
						+ "p4MH8WvWNrshpfU9OSaPBRdbMus6+J3qLuz+uQaqyMI/Jq5LtBNuudnvBCMVW7v7\n"
						+ "L+mjRoRG5Rn1VRrlcraX9zrgCw+mlQA1zUxXf4zppGEiM56/u2EyPaqTs1DNfeTM\n"
						+ "3th2L/NS1SFoGAaPT7pzKCBlpr1pvG00QKeuMCXRPGa+9VqqjPqE/nic63hTC3s2\n"
						+ "nEG08H9bAgMBAAECggEABHb08RuVGcXDWx7V2kgx9wwUuBeVutXaDb620Z1ew7w6\n"
						+ "LaucVoJnr5DPgCLQ1nadjI8jp/C6Z+uvQn70iV+lmsPSjbFG61RT/4cD1tx5Z3B6\n"
						+ "I8nryANKkhPq1a70lXleQ2Zekm9y/tTvlzGgTr/0R3+5BF6+hlvoeIfoh1MASaIV\n"
						+ "KwEeisN0/bUjbrVksdcDXdmonW1Zxqxhs2nQgK6kNOAAkr9qLc6PdNhDTNhfwYno\n"
						+ "52OdrfS+G8+9c3n8ndATF9wiRwVOMRM3dbcKFNz/dJjVYIBpdfBJF+x51Rlqmm3N\n"
						+ "gSubTrz54FCOB6VVzhMgIn+1qz0Dg6dwCbMiYTMAoQKBgQDjMWkKiErMq3Rz+QR2\n"
						+ "dAdFTNJ25Ge0nl6L6OOvc+ds+OYkzPfANXmXJWOKwDhMuxJWY87gs798kkJS26HN\n"
						+ "HmUTHSgPnaE6pXVlfKz3IcaGlbIfmS/yR/OqhWAxm1arFzu9riFbO+XrkCmY/KL9\n"
						+ "GE0t9Ghzgw8zt0DTxU99qq9WYQKBgQDCeBY9bak0R0GJf/rhzm3HxHjzFXjfbEvJ\n"
						+ "9/psXEBjSR3ZL3im8X1cVWrOMTSTk1vMVWsHX+F658yMdfbdoAJIATKr4qSgCfZ6\n"
						+ "yy2jqHcwXICmMI/pEodt6rkGW7y8NaoazbjNeB/Eo5f8qT6p8Be0muCYXvc8CjrD\n"
						+ "sKs8ADD3OwKBgQCEnb/x7PK0m9SDKbVoK3xfAGPOEK9UaClKQ+w7600IeBVnH9ny\n"
						+ "cYSDLMj5IhD7ASvID8Sft2ysG3fpg9jjsb1QNfG/g8SsRg7L6cXRD/8haloqRbIO\n"
						+ "G9/pBqfK7SDfB9XQd8dyyPkB0wnlCntdf4T1sTgTTUpfZrXJp+Apu6tToQKBgBtx\n"
						+ "2SmoLUX8fGpMTnrJqEKWHr+nmxxk6zlAru3WAxw/+F9rTKq60AdU4rLgzNu64yyu\n"
						+ "LWGoDWlMB1kXWNSkPU4uJRmO7c7MOSXRQMqk/tUraNiLZ/PrsoM3qg8UqUkihbQs\n"
						+ "rlUJC+qzb8Kvm/Fdueq6JNI8LMYjZ3GwoCnimQZ7AoGAPJarZN8kKE7Qzdm67diN\n"
						+ "s+AZ+k0NzLxoCQlNjGuytZ6x/2lY+ajRwvwMmKR7KKzULZHjoOSsFVE+t0hKL4Xe\n"
						+ "TQGCeU0nS5AM5qHK+617rWYiNL7+R5Y1+Ep9/7df2j3jx1YOx4I3RoFMjqj94b0X\n"
						+ "BLWWYDNLaL2y9IW0UKZvIls=\n"
						+ "-----END PRIVATE KEY-----";

		/** 테스트용 2048-bit RSA Public Key PEM (X.509, BEGIN PUBLIC KEY) */
		static final String TEST_PUBLIC_KEY_PEM =
				"-----BEGIN PUBLIC KEY-----\n"
						+ "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEArJYEf/tXtR/gN6TA+/th\n"
						+ "VqTELfcIUQ/lxt9ATItfRHPDG9naxP0h+VPnpkqzMEGlXUoEZh3EV8BEQxtnKTLE\n"
						+ "fmbKMQxsKG7fnv1+DVfSNf/VSLjqKbfO3s+BNpfzmoXIntlshIf4dfVPqaeDB/Fr\n"
						+ "1ja7IaX1PTkmjwUXWzLrOvid6i7s/rkGqsjCPyauS7QTbrnZ7wQjFVu7+y/po0aE\n"
						+ "RuUZ9VUa5XK2l/c64AsPppUANc1MV3+M6aRhIjOev7thMj2qk7NQzX3kzN7Ydi/z\n"
						+ "UtUhaBgGj0+6cyggZaa9abxtNECnrjAl0TxmvvVaqoz6hP54nOt4Uwt7NpxBtPB/\n"
						+ "WwIDAQAB\n"
						+ "-----END PUBLIC KEY-----";
	}
}
