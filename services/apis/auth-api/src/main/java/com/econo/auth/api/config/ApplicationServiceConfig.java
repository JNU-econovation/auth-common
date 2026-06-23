package com.econo.auth.api.config;

import com.econo.auth.api.application.service.RouteBootstrapService;
import com.econo.auth.client.application.repository.SasClientRegistrar;
import com.econo.auth.client.application.repository.SasRedirectUriManager;
import com.econo.auth.client.application.repository.ServiceClientRepository;
import com.econo.auth.client.application.repository.ServiceRouteRepository;
import com.econo.auth.client.application.service.GatewayRefreshClient;
import com.econo.auth.client.application.service.ManageOwnClientService;
import com.econo.auth.client.application.service.ManageRouteService;
import com.econo.auth.client.application.service.ProtectedPathPolicy;
import com.econo.auth.client.application.service.RegisterOAuthClientService;
import com.econo.auth.client.application.service.RouteNamespaceExtractor;
import com.econo.auth.client.application.service.RouteValidator;
import com.econo.auth.member.application.repository.MemberRepository;
import com.econo.auth.member.application.repository.PasswordHasher;
import com.econo.auth.member.application.service.SignupService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 유스케이스 빈 등록 설정 — 계층형 아키텍처 원칙에 따라 어댑터(auth-api) 측에서 빈 등록 책임을 가짐
 *
 * <p>{@code LoginService}는 SAS 도입으로 {@link
 * com.econo.auth.api.config.security.MemberUserDetailsService}로 대체되어 제거됨.
 *
 * <p>{@code LoginRedirectResolver}는 login lib의 {@code @Service} 자동 등록으로 전환되어 이 설정에서 제거됨.
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
	 * ProtectedPathPolicy 빈 등록 — 보호 경로 값을 소유하는 구현체
	 *
	 * <p>보호 경로 목록은 배포 환경(게이트웨이 정적 라우트)에 종속되므로 소비자 앱인 auth-api가 제공한다.
	 *
	 * @return ProtectedPathPolicy 구현체
	 */
	@Bean
	public ProtectedPathPolicy protectedPathPolicy() {
		return new ProtectedPathPolicyImpl();
	}

	/**
	 * RouteValidator 빈 등록 — 라우트 검증 공유 (ManageRouteService 등)
	 *
	 * @param serviceRouteRepository ServiceRouteRepository 포트 구현체
	 * @param protectedPathPolicy 보호 경로 판정 포트 구현체
	 * @return RouteValidator 인스턴스
	 */
	@Bean
	public RouteValidator routeValidator(
			ServiceRouteRepository serviceRouteRepository, ProtectedPathPolicy protectedPathPolicy) {
		return new RouteValidator(serviceRouteRepository, protectedPathPolicy);
	}

	/**
	 * ManageRouteService 빈 등록
	 *
	 * @param serviceRouteRepository ServiceRouteRepository 포트 구현체
	 * @param gatewayRefreshClient GatewayRefreshClient 구현체
	 * @param routeValidator 공유 라우트 검증기
	 * @return ManageRouteService 인스턴스
	 */
	@Bean
	public ManageRouteService manageRouteService(
			ServiceRouteRepository serviceRouteRepository,
			GatewayRefreshClient gatewayRefreshClient,
			RouteValidator routeValidator) {
		return new ManageRouteService(serviceRouteRepository, gatewayRefreshClient, routeValidator);
	}

	/**
	 * RouteBootstrapService 빈 등록
	 *
	 * @param serviceRouteRepository ServiceRouteRepository 포트 구현체
	 * @return RouteBootstrapService 인스턴스
	 */
	@Bean
	public RouteBootstrapService routeBootstrapService(
			ServiceRouteRepository serviceRouteRepository) {
		return new RouteBootstrapService(serviceRouteRepository);
	}

	/**
	 * RegisterOAuthClientService 빈 등록
	 *
	 * <p>@Service 자동 스캔 대신 수동 등록 — GatewayRefreshClient(auth-api 소속 빈)를 명시적으로 주입하기 위함.
	 * ManageRouteService와 동일 패턴.
	 *
	 * @param sasClientRegistrar SAS 클라이언트 등록 포트
	 * @param serviceClientRepository ServiceClient 저장소 포트
	 * @param passwordEncoder 비밀번호 해시 인코더
	 * @param serviceRouteRepository ServiceRoute 저장소 포트
	 * @param gatewayRefreshClient Gateway refresh 트리거 포트
	 * @param routeValidator 라우트 검증기
	 * @return RegisterOAuthClientService 인스턴스
	 */
	@Bean
	public RegisterOAuthClientService registerOAuthClientService(
			SasClientRegistrar sasClientRegistrar,
			ServiceClientRepository serviceClientRepository,
			PasswordEncoder passwordEncoder,
			ServiceRouteRepository serviceRouteRepository,
			GatewayRefreshClient gatewayRefreshClient,
			RouteValidator routeValidator) {
		return new RegisterOAuthClientService(
				sasClientRegistrar,
				serviceClientRepository,
				passwordEncoder,
				serviceRouteRepository,
				gatewayRefreshClient,
				routeValidator,
				new RouteNamespaceExtractor());
	}

	/**
	 * ManageOwnClientService 빈 등록 — 셀프 클라이언트 관리 유스케이스
	 *
	 * <p>@Service 자동 스캔 대신 수동 등록 — GatewayRefreshClient(auth-api 소속 빈)를 명시적으로 주입하기 위함.
	 *
	 * @param serviceClientRepository ServiceClient 저장소 포트
	 * @param serviceRouteRepository ServiceRoute 저장소 포트
	 * @param sasClientRegistrar SAS 클라이언트 등록 포트
	 * @param sasRedirectUriManager SAS redirectUri 관리 포트
	 * @param gatewayRefreshClient Gateway refresh 트리거 포트
	 * @param routeValidator 라우트 검증기
	 * @return ManageOwnClientService 인스턴스
	 */
	@Bean
	public ManageOwnClientService manageOwnClientService(
			ServiceClientRepository serviceClientRepository,
			ServiceRouteRepository serviceRouteRepository,
			SasClientRegistrar sasClientRegistrar,
			SasRedirectUriManager sasRedirectUriManager,
			GatewayRefreshClient gatewayRefreshClient,
			RouteValidator routeValidator) {
		return new ManageOwnClientService(
				serviceClientRepository,
				serviceRouteRepository,
				sasClientRegistrar,
				sasRedirectUriManager,
				gatewayRefreshClient,
				routeValidator,
				new RouteNamespaceExtractor());
	}
}
