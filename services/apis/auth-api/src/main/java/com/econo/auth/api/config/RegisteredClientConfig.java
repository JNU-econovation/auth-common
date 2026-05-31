package com.econo.auth.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/**
 * RegisteredClientRepository 빈 등록 — AdminClientController에서 OAuth2 클라이언트 관리에 사용
 *
 * <p>SAS 인가 서버는 제거됐지만 {@link RegisteredClientRepository}는 클라이언트 등록 API에서 계속 사용.
 */
@Configuration
public class RegisteredClientConfig {

	@Bean
	public RegisteredClientRepository registeredClientRepository(JdbcOperations jdbcOperations) {
		return new JdbcRegisteredClientRepository(jdbcOperations);
	}
}
