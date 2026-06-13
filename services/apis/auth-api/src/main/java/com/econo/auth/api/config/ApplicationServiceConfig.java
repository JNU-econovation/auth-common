package com.econo.auth.api.config;

import com.econo.auth.api.application.service.LoginRedirectResolver;
import com.econo.auth.client.application.usecase.ClientRedirectUriUseCase;
import com.econo.auth.member.application.repository.MemberRepository;
import com.econo.auth.member.application.repository.PasswordHasher;
import com.econo.auth.member.application.service.SignupService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * member 유스케이스 빈 등록 설정 — 계층형 아키텍처 원칙에 따라 어댑터(auth-api) 측에서 빈 등록 책임을 가짐
 *
 * <p>{@code LoginService}는 SAS 도입으로 {@link
 * com.econo.auth.api.config.security.MemberUserDetailsService}로 대체되어 제거됨.
 */
@Configuration
public class ApplicationServiceConfig {

	/**
	 * SignupService 빈 등록
	 *
	 * @param memberRepository MemberRepository 포트 구현체
	 * @param passwordHasher PasswordHasher 포트 구현체
	 * @return SignupService 인스턴스
	 */
	@Bean
	public SignupService signupService(
			MemberRepository memberRepository, PasswordHasher passwordHasher) {
		return new SignupService(memberRepository, passwordHasher);
	}

	/**
	 * LoginRedirectResolver 빈 등록
	 *
	 * @param clientRedirectUriUseCase ClientRedirectUriUseCase 포트 구현체
	 * @return LoginRedirectResolver 인스턴스
	 */
	@Bean
	public LoginRedirectResolver loginRedirectResolver(
			ClientRedirectUriUseCase clientRedirectUriUseCase) {
		return new LoginRedirectResolver(clientRedirectUriUseCase);
	}
}
