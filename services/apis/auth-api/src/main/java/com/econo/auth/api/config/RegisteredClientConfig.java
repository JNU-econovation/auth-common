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
 * JdbcRegisteredClientRepository 빈 등록 및 1st-party public client seed 설정
 *
 * <p>앱 기동 시 {@code FIRST_PARTY_CLIENT_ID}에 해당하는 클라이언트가 DB에 없으면 {@code save()}로 등록한다 (멱등).
 * Authorization Code + PKCE + Refresh Token 그랜트를 지원하는 public client로 등록된다.
 */
@Configuration
public class RegisteredClientConfig {

	@Value("${FIRST_PARTY_CLIENT_ID:econo-spa}")
	private String clientId;

	@Value("${FIRST_PARTY_REDIRECT_URI:http://localhost:3000/callback}")
	private String redirectUri;

	/**
	 * JdbcRegisteredClientRepository 빈 등록 및 1st-party client seed
	 *
	 * @param jdbcOperations DataSource 기반 JdbcOperations
	 * @return {@link RegisteredClientRepository}
	 */
	@Bean
	public RegisteredClientRepository registeredClientRepository(JdbcOperations jdbcOperations) {
		JdbcRegisteredClientRepository repository = new JdbcRegisteredClientRepository(jdbcOperations);

		// 멱등 seed — clientId 기준으로 존재 여부 확인 후 없으면 등록
		if (repository.findByClientId(clientId) == null) {
			repository.save(firstPartyPublicClient());
		}

		return repository;
	}

	/** 자사 1st-party public client (PKCE 필수, consent 자동 승인) */
	private RegisteredClient firstPartyPublicClient() {
		// 고정 UUID 사용 — 기동마다 id가 바뀌면 oauth2_authorization의 registered_client_id 참조가 깨짐
		return RegisteredClient.withId("00000000-0000-0000-0000-000000000001")
				.clientId(clientId)
				.clientName("Econo SPA")
				.clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
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
