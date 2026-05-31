package com.econo.auth.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/**
 * SAS JDBC 인가 서비스 빈 등록 — Authorization Code / Token / Consent 영속화
 *
 * <p>직접 JWT 흐름({@code POST /api/v1/auth/login})을 주로 사용하므로 SAS Authorization Code 흐름은 사용하지 않는다. 단,
 * SAS는 JWKS 엔드포인트({@code /oauth2/jwks})와 OIDC Discovery를 제공하므로 유지한다.
 */
@Configuration
public class OAuth2AuthorizationServiceConfig {

	@Bean
	public OAuth2AuthorizationService authorizationService(
			JdbcOperations jdbcOperations, RegisteredClientRepository registeredClientRepository) {
		return new JdbcOAuth2AuthorizationService(jdbcOperations, registeredClientRepository);
	}

	@Bean
	public OAuth2AuthorizationConsentService authorizationConsentService(
			JdbcOperations jdbcOperations, RegisteredClientRepository registeredClientRepository) {
		return new JdbcOAuth2AuthorizationConsentService(jdbcOperations, registeredClientRepository);
	}
}
