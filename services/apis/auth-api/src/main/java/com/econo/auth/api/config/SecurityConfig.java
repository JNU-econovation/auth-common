package com.econo.auth.api.config;

import com.econo.auth.api.adapter.in.web.TokenCookieManager;
import com.econo.auth.api.application.LoginRedirectResolver;
import com.econo.auth.api.application.LoginTokenService;
import com.econo.auth.api.filter.JsonLoginAuthenticationFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * SecurityFilterChain — JWT Stateless 인증
 *
 * <p>세션 없이 JWT 기반으로 동작한다. {@link JsonLoginAuthenticationFilter}가 성공 시 AT/RT를 발급한다. WEB 분기는 302
 * 리다이렉트(clientId로 redirect_uri 조회), APP 분기는 200 OK + body.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
@EnableConfigurationProperties(AuthRedirectProperties.class)
public class SecurityConfig {

	private final ObjectMapper objectMapper;

	@Bean
	public SecurityFilterChain appSecurityFilterChain(
			HttpSecurity http,
			@Qualifier("memberAuthenticationManager")
					@org.springframework.beans.factory.annotation.Autowired(required = false)
					AuthenticationManager memberAuthenticationManager,
			@org.springframework.beans.factory.annotation.Autowired(required = false)
					LoginTokenService loginTokenService,
			@org.springframework.beans.factory.annotation.Autowired(required = false)
					TokenCookieManager cookieManager,
			@org.springframework.beans.factory.annotation.Autowired(required = false)
					LoginRedirectResolver loginRedirectResolver,
			AuthRedirectProperties redirectProperties)
			throws Exception {
		http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.csrf(AbstractHttpConfigurer::disable)
				.authorizeHttpRequests(
						auth ->
								auth.requestMatchers(
												"/api/v1/auth/signup",
												"/api/v1/auth/login",
												"/api/v1/auth/logout",
												"/api/v1/auth/reissue",
												"/api/v1/admin/jwks", // Gateway JWT 검증용 (내부 직접 호출)
												"/api/v1/internal/**", // CLI 전용 내부 API (X-Internal-Api-Key 보호)
												"/.well-known/**") // OIDC Discovery
										.permitAll()
										// Gateway가 AT 검증 후 전달 — auth-api 내부는 Gateway 인증에 의존
										// admin 역할 체크는 컨트롤러의 X-User-Passport 파싱으로 처리
										.requestMatchers("/api/v1/admin/**", "/api/v1/members/**")
										.permitAll()
										// 셀프 등록 — X-User-Passport 기반 인증을 컨트롤러에서 직접 처리
										.requestMatchers("/api/v1/clients/**")
										.permitAll()
										.anyRequest()
										.authenticated())
				.exceptionHandling(ex -> ex.authenticationEntryPoint(apiAuthenticationEntryPoint()));

		if (memberAuthenticationManager != null
				&& loginTokenService != null
				&& cookieManager != null
				&& loginRedirectResolver != null) {
			JsonLoginAuthenticationFilter loginFilter =
					new JsonLoginAuthenticationFilter(
							memberAuthenticationManager,
							objectMapper,
							loginTokenService,
							cookieManager,
							loginRedirectResolver,
							redirectProperties.getDefaultUrl());
			http.addFilterBefore(loginFilter, UsernamePasswordAuthenticationFilter.class);
		}

		return http.build();
	}

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder(12);
	}

	@Bean(name = "memberAuthenticationManager")
	@org.springframework.boot.autoconfigure.condition.ConditionalOnBean(
			name = "memberUserDetailsService")
	public AuthenticationManager memberAuthenticationManager(
			@Qualifier("memberUserDetailsService") UserDetailsService memberUserDetailsService) {
		DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
		provider.setUserDetailsService(memberUserDetailsService);
		provider.setPasswordEncoder(passwordEncoder());
		return new ProviderManager(provider);
	}

	private AuthenticationEntryPoint apiAuthenticationEntryPoint() {
		return (request, response, authException) -> {
			response.setContentType("application/json;charset=UTF-8");
			response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
			response.getWriter().write("{\"errorCode\":\"UNAUTHORIZED\",\"message\":\"인증이 필요합니다.\"}");
		};
	}
}
