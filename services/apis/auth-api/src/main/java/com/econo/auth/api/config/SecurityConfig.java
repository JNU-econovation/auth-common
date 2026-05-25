package com.econo.auth.api.config;

import com.econo.auth.api.filter.JsonLoginAuthenticationFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

/**
 * 앱용 SecurityFilterChain 설정 — {@code @Order(2)}
 *
 * <p>세션 기반({@link SessionCreationPolicy#IF_REQUIRED})으로 전환. CSRF는 {@link
 * CookieCsrfTokenRepository}를 사용하되 {@code /api/v1/auth/login}, {@code /api/v1/auth/signup}, {@code
 * /api/v1/auth/logout}을 제외. 커스텀 JSON 인증 필터({@link JsonLoginAuthenticationFilter})를 삽입한다.
 *
 * <p>{@code @EnableWebSecurity}는 이 클래스에만 선언하여 중복 선언을 방지한다.
 */
@Configuration
@EnableWebSecurity
@Order(2)
@RequiredArgsConstructor
public class SecurityConfig {

	private final ObjectMapper objectMapper;

	/**
	 * 앱용 SecurityFilterChain — {@code @Order(2)}
	 *
	 * <p>{@link JsonLoginAuthenticationFilter}는 {@link HttpSecurity#getSharedObject}를 통해 {@link
	 * org.springframework.security.authentication.AuthenticationManager}를 얻어 인라인 생성한다. 이 방식은
	 * {@code @WebMvcTest} 슬라이스 환경에서도 동작한다.
	 *
	 * @param http HttpSecurity
	 * @return {@link SecurityFilterChain}
	 * @throws Exception 설정 오류
	 */
	@Bean
	@Order(2)
	public SecurityFilterChain appSecurityFilterChain(HttpSecurity http) throws Exception {
		http.securityMatcher(
						request -> {
							// SAS 필터체인(@Order(1))이 처리하지 않는 요청만 처리
							String path = request.getRequestURI();
							return !path.startsWith("/oauth2/")
									&& !path.startsWith("/.well-known/")
									&& !path.equals("/userinfo");
						})
				.sessionManagement(
						session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
				.csrf(
						csrf ->
								csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
										.ignoringRequestMatchers(
												"/api/v1/auth/login", "/api/v1/auth/signup", "/api/v1/auth/logout"))
				.authorizeHttpRequests(
						auth ->
								auth.requestMatchers(
												"/api/v1/auth/signup", "/api/v1/auth/login", "/api/v1/auth/logout")
										.permitAll()
										.anyRequest()
										.authenticated());

		// HttpSecurity를 통해 AuthenticationManager를 얻어 필터 인라인 생성
		// @WebMvcTest 슬라이스 환경에서는 AuthenticationManager가 null일 수 있으므로 조건부 추가
		org.springframework.security.authentication.AuthenticationManager authManager =
				http.getSharedObject(
						org.springframework.security.authentication.AuthenticationManager.class);
		if (authManager != null) {
			JsonLoginAuthenticationFilter jsonLoginFilter =
					new JsonLoginAuthenticationFilter(authManager, objectMapper);
			http.addFilterBefore(jsonLoginFilter, UsernamePasswordAuthenticationFilter.class);
		}

		return http.build();
	}

	/**
	 * BCrypt PasswordEncoder 빈 등록 (cost=12, BCryptPasswordHasherAdapter와 일치)
	 *
	 * @return {@link PasswordEncoder}
	 */
	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder(12);
	}
}
