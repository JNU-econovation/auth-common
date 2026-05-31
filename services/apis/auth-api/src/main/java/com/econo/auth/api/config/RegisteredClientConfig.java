package com.econo.auth.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;

/**
 * JdbcRegisteredClientRepository 빈 등록 및 1st-party client seed
 *
 * <p>인증 방식: {@code POST /api/v1/auth/login} 직접 JWT 발급이 주 방식. SAS는 JWKS 엔드포인트({@code /oauth2/jwks})와
 * OIDC Discovery를 제공하기 위해 유지한다.
 *
 * <p>향후 서드파티 앱 연동이 필요하면 Authorization Code + PKCE 흐름 활성화 검토.
 */
@Configuration
public class RegisteredClientConfig {

	@Value("${FIRST_PARTY_CLIENT_ID:econo-spa}")
	private String clientId;

	@Value("${FIRST_PARTY_REDIRECT_URI:http://localhost:3000/callback}")
	private String redirectUri;

	@Bean
	public RegisteredClientRepository registeredClientRepository(JdbcOperations jdbcOperations) {
		JdbcRegisteredClientRepository repository = new JdbcRegisteredClientRepository(jdbcOperations);
		if (repository.findByClientId(clientId) == null) {
			repository.save(firstPartyPublicClient());
		}
		return repository;
	}

	/** 1st-party public client — JWKS/OIDC Discovery 용도로 등록 유지 */
	private RegisteredClient firstPartyPublicClient() {
		return RegisteredClient.withId("00000000-0000-0000-0000-000000000001")
				.clientId(clientId)
				.clientName("Econo SPA")
				.clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.redirectUri(redirectUri)
				.scope(OidcScopes.OPENID)
				.scope(OidcScopes.PROFILE)
				.clientSettings(
						ClientSettings.builder()
								.requireProofKey(true)
								.requireAuthorizationConsent(false)
								.build())
				.build();
	}
}
