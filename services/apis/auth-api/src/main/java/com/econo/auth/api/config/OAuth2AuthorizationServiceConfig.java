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
 * <p>{@link JdbcOAuth2AuthorizationService}가 Authorization Code, Access Token, Refresh Token, ID
 * Token을 {@code oauth2_authorization} 테이블에 저장한다. {@link JdbcOAuth2AuthorizationConsentService}가
 * scope 동의 정보를 {@code oauth2_authorization_consent} 테이블에 저장한다.
 */
@Configuration
public class OAuth2AuthorizationServiceConfig {

	/**
	 * JdbcOAuth2AuthorizationService 빈 등록
	 *
	 * @param jdbcOperations DataSource 기반 JdbcOperations
	 * @param registeredClientRepository 등록된 클라이언트 저장소
	 * @return {@link OAuth2AuthorizationService}
	 */
	@Bean
	public OAuth2AuthorizationService authorizationService(
			JdbcOperations jdbcOperations, RegisteredClientRepository registeredClientRepository) {
		return new JdbcOAuth2AuthorizationService(jdbcOperations, registeredClientRepository);
	}

	/**
	 * JdbcOAuth2AuthorizationConsentService 빈 등록
	 *
	 * @param jdbcOperations DataSource 기반 JdbcOperations
	 * @param registeredClientRepository 등록된 클라이언트 저장소
	 * @return {@link OAuth2AuthorizationConsentService}
	 */
	@Bean
	public OAuth2AuthorizationConsentService authorizationConsentService(
			JdbcOperations jdbcOperations, RegisteredClientRepository registeredClientRepository) {
		return new JdbcOAuth2AuthorizationConsentService(jdbcOperations, registeredClientRepository);
	}
}
