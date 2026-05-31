package com.econo.auth.api.config;

import com.econo.auth.api.security.MemberUserDetails;
import com.econo.auth.api.security.MemberUserDetailsMixin;
import com.econo.auth.core.member.domain.Member;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.jackson2.SecurityJackson2Modules;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.jackson2.OAuth2AuthorizationServerJackson2Module;

/**
 * SAS JDBC 인가 서비스 빈 등록 — Authorization Code / Token / Consent 영속화
 *
 * <p>JdbcOAuth2AuthorizationService는 authorization을 PostgreSQL에 Jackson JSON으로 직렬화한다. 커스텀
 * MemberUserDetails가 Spring Security Jackson 허용 목록에 없으므로 ObjectMapper에 명시적으로 등록해야 한다.
 */
@Configuration
public class OAuth2AuthorizationServiceConfig {

	@Bean
	public OAuth2AuthorizationService authorizationService(
			JdbcOperations jdbcOperations, RegisteredClientRepository registeredClientRepository) {
		// Read + Write 모두 동일한 ObjectMapper 사용
		ObjectMapper mapper = new ObjectMapper();
		ClassLoader cl = OAuth2AuthorizationServiceConfig.class.getClassLoader();
		mapper.registerModules(SecurityJackson2Modules.getModules(cl));
		mapper.registerModule(new OAuth2AuthorizationServerJackson2Module());
		mapper.addMixIn(MemberUserDetails.class, MemberUserDetailsMixin.class);
		mapper.addMixIn(Member.class, MemberMixin.class);

		JdbcOAuth2AuthorizationService service =
				new JdbcOAuth2AuthorizationService(jdbcOperations, registeredClientRepository);

		// Read mapper
		JdbcOAuth2AuthorizationService.OAuth2AuthorizationRowMapper rowMapper =
				new JdbcOAuth2AuthorizationService.OAuth2AuthorizationRowMapper(registeredClientRepository);
		rowMapper.setObjectMapper(mapper);
		service.setAuthorizationRowMapper(rowMapper);

		// Write mapper — 동일한 ObjectMapper 사용 (RT 직렬화 포함)
		JdbcOAuth2AuthorizationService.OAuth2AuthorizationParametersMapper parametersMapper =
				new JdbcOAuth2AuthorizationService.OAuth2AuthorizationParametersMapper();
		parametersMapper.setObjectMapper(mapper);
		service.setAuthorizationParametersMapper(parametersMapper);

		return service;
	}

	@Bean
	public OAuth2AuthorizationConsentService authorizationConsentService(
			JdbcOperations jdbcOperations, RegisteredClientRepository registeredClientRepository) {
		return new JdbcOAuth2AuthorizationConsentService(jdbcOperations, registeredClientRepository);
	}

	/** Member 도메인 Jackson 믹스인 — restore() 팩토리 메서드로 역직렬화 */
	@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)
	@JsonAutoDetect(
			fieldVisibility = JsonAutoDetect.Visibility.ANY,
			getterVisibility = JsonAutoDetect.Visibility.NONE,
			isGetterVisibility = JsonAutoDetect.Visibility.NONE)
	@JsonIgnoreProperties(ignoreUnknown = true)
	abstract static class MemberMixin {

		@com.fasterxml.jackson.annotation.JsonCreator
		static com.econo.auth.core.member.domain.Member restore(
				@com.fasterxml.jackson.annotation.JsonProperty("id") Long id,
				@com.fasterxml.jackson.annotation.JsonProperty("name") String name,
				@com.fasterxml.jackson.annotation.JsonProperty("loginId") String loginId,
				@com.fasterxml.jackson.annotation.JsonProperty("hashedPassword") String hashedPassword,
				@com.fasterxml.jackson.annotation.JsonProperty("generation") Integer generation,
				@com.fasterxml.jackson.annotation.JsonProperty("status")
						com.econo.auth.core.member.domain.MemberStatus status,
				@com.fasterxml.jackson.annotation.JsonProperty("createdAt")
						java.time.LocalDateTime createdAt) {
			return com.econo.auth.core.member.domain.Member.restore(
					id, name, loginId, hashedPassword, generation, status, createdAt);
		}
	}
}
