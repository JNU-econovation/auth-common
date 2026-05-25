package com.econo.auth.api.config;

import com.econo.auth.core.member.application.port.out.MemberRepository;
import com.econo.auth.core.member.application.port.out.PasswordHasher;
import com.econo.auth.core.member.application.usecase.SignupService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * auth-core 유스케이스 빈 등록 설정 — 헥사고날 아키텍처 원칙에 따라 어댑터(auth-api) 측에서 빈 등록 책임을 가짐
 *
 * <p>{@code LoginService}는 SAS 도입으로 {@link com.econo.auth.api.security.MemberUserDetailsService}로
 * 대체되어 제거됨.
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
}
