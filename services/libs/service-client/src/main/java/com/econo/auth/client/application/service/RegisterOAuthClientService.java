package com.econo.auth.client.application.service;

import com.econo.auth.client.application.domain.GrantType;
import com.econo.auth.client.application.domain.ServiceClient;
import com.econo.auth.client.application.domain.ServiceRoute;
import com.econo.auth.client.application.repository.SasClientRegistrar;
import com.econo.auth.client.application.repository.ServiceClientRepository;
import com.econo.auth.client.application.repository.ServiceRouteRepository;
import com.econo.auth.client.application.usecase.RegisterOAuthClientUseCase;
import com.econo.auth.client.exception.ClientLimitExceededException;
import com.econo.auth.client.exception.DuplicateClientNameException;
import com.econo.auth.client.exception.RedirectUriRequiredException;
import com.econo.auth.client.exception.RouteNamespaceTakenException;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * OAuth 클라이언트 등록 서비스
 *
 * <p>프론트엔드/모바일 전용 — 항상 authorization_code (PKCE) 클라이언트로 등록한다.
 *
 * <p>ApplicationServiceConfig에서 @Bean 등록 (ServiceClientAutoConfiguration @ComponentScan 대상에서 제외하기
 * 위해 @Service 사용 안 함). GatewayRefreshClient 구현체(GatewayRefreshClientImpl)가 auth-api 소속 빈이어서
 * service-client 스캔 범위 밖이므로 수동 @Bean 와이어링이 필요하다.
 */
@Slf4j
@RequiredArgsConstructor
public class RegisterOAuthClientService implements RegisterOAuthClientUseCase {

	private final SasClientRegistrar sasClientRegistrar;
	private final ServiceClientRepository serviceClientRepository;
	private final PasswordEncoder passwordEncoder;
	private final ServiceRouteRepository serviceRouteRepository;
	private final GatewayRefreshClient gatewayRefreshClient;
	private final RouteValidator routeValidator;
	private final RouteNamespaceExtractor namespaceExtractor;

	/**
	 * OAuth 클라이언트 등록 (authorization_code 고정)
	 *
	 * @throws RedirectUriRequiredException redirectUris가 없을 때
	 * @throws DuplicateClientNameException clientName 중복 시
	 */
	@Override
	@Transactional
	public RegisterOAuthClientResult register(RegisterOAuthClientCommand command) {
		if (command.clientName() == null) {
			throw new IllegalArgumentException("clientName은 필수입니다.");
		}
		if (command.redirectUris() == null || command.redirectUris().isEmpty()) {
			throw new RedirectUriRequiredException();
		}

		if (serviceClientRepository.existsByClientName(command.clientName())) {
			throw new DuplicateClientNameException(command.clientName());
		}

		String clientId = UUID.randomUUID().toString();
		// SAS JdbcRegisteredClientRepository와 service_client JPA는 동일 DataSource
		// (application.yml 단일 DataSource + JpaTransactionManager)를 사용하므로
		// @Transactional 경계 안에서 동일 Connection으로 처리되어 원자성이 보장된다.
		sasClientRegistrar.registerAuthorizationCodeClient(
				clientId, command.clientName(), command.redirectUris());
		serviceClientRepository.save(
				ServiceClient.create(clientId, command.clientName(), GrantType.AUTHORIZATION_CODE, null));

		return new RegisterOAuthClientResult(clientId);
	}

	/**
	 * 인증된 회원의 셀프 SSO 클라이언트 등록 (authorization_code 고정, 1인 5개 제한)
	 *
	 * <p>pathPrefix와 upstreamUrl이 둘 다 non-blank이면 동일 @Transactional 경계 안에서 ServiceRoute를 원자적으로 생성하고
	 * 커밋 후 게이트웨이 refresh를 트리거한다.
	 *
	 * @param command 셀프 등록 명령 (clientName, redirectUris, ownerId, pathPrefix, upstreamUrl)
	 * @return 등록된 clientId와 1회 노출 clientSecret, 라우트 생성 시 라우트 정보 포함
	 * @throws IllegalArgumentException clientName이 null 또는 blank일 때
	 * @throws RedirectUriRequiredException redirectUris가 없을 때
	 * @throws ClientLimitExceededException 해당 회원의 등록 클라이언트 수가 5개 이상일 때
	 * @throws DuplicateClientNameException clientName 중복 시
	 */
	@Override
	@Transactional
	public SelfRegisterOAuthClientResult selfRegister(SelfRegisterOAuthClientCommand command) {
		// (1) 입력값 검증 — DB 조회보다 앞에 위치해야 null ownerId 등으로 인한 NPE를 방지
		if (command.clientName() == null || command.clientName().isBlank()) {
			throw new IllegalArgumentException("clientName은 필수입니다.");
		}

		// (2) redirectUris 필수 검증
		if (command.redirectUris() == null || command.redirectUris().isEmpty()) {
			throw new RedirectUriRequiredException();
		}

		// (3) 1인 5개 제한 — count → save 사이 레이스 조건으로 극히 드물게 5개 초과 저장 가능.
		//     향후 DB 락(SELECT FOR UPDATE) 또는 분산 락으로 강화 가능.
		if (serviceClientRepository.countByOwnerId(command.ownerId()) >= 5) {
			throw new ClientLimitExceededException();
		}

		// (4) clientName 중복 검증
		if (serviceClientRepository.existsByClientName(command.clientName())) {
			throw new DuplicateClientNameException(command.clientName());
		}

		String clientId = UUID.randomUUID().toString();
		String rawSecret = UUID.randomUUID().toString();
		String secretHash = passwordEncoder.encode(rawSecret);

		// SAS JdbcRegisteredClientRepository와 service_client JPA는 동일 DataSource
		// (application.yml 단일 DataSource + JpaTransactionManager)를 사용하므로
		// @Transactional 경계 안에서 동일 Connection으로 처리되어 원자성이 보장된다.
		sasClientRegistrar.registerAuthorizationCodeClient(
				clientId, command.clientName(), command.redirectUris());
		serviceClientRepository.save(
				ServiceClient.create(
						clientId,
						command.clientName(),
						GrantType.AUTHORIZATION_CODE,
						null,
						command.ownerId(),
						secretHash));

		// (5) 라우트 생성 분기 — pathPrefix와 upstreamUrl이 둘 다 non-blank일 때만 진입
		boolean hasPathPrefix = command.pathPrefix() != null && !command.pathPrefix().isBlank();
		boolean hasUpstreamUrl = command.upstreamUrl() != null && !command.upstreamUrl().isBlank();

		if (hasPathPrefix && hasUpstreamUrl) {
			String pathPrefix = command.pathPrefix();
			String upstreamUrl = command.upstreamUrl();

			// ① 네임스페이스 추출 및 포맷 검증 — RouteNamespaceInvalidException 400
			String namespace = namespaceExtractor.extract(pathPrefix);

			// ② 네임스페이스 선점 확인 — 타 owner이면 RouteNamespaceTakenException 403
			Optional<Long> existingOwner = serviceRouteRepository.findNamespaceOwner(namespace);
			if (existingOwner.isPresent() && !existingOwner.get().equals(command.ownerId())) {
				throw new RouteNamespaceTakenException(namespace);
			}

			// ③ upstreamUrl SSRF 검증 — RouteUpstreamInvalidException 400
			routeValidator.validateUpstreamUrl(upstreamUrl);

			// ④ pathPrefix 보호경로/중복 검증 — RouteProtectedException 403, RoutePathConflictException 409
			routeValidator.validatePathPrefix(pathPrefix);

			// ⑤ 라우트 저장 (동일 @Transactional 경계 — 클라이언트 저장과 원자성 보장)
			ServiceRoute saved =
					serviceRouteRepository.save(
							ServiceRoute.create(pathPrefix, upstreamUrl, true, command.ownerId(), clientId));

			// ⑥ 게이트웨이 refresh 트리거 — afterCommit에서 실행 (커밋 전 호출 방지)
			triggerRefresh();

			return new SelfRegisterOAuthClientResult(
					clientId,
					rawSecret,
					saved.routeId(),
					saved.pathPrefix(),
					saved.upstreamUrl(),
					saved.enabled());
		}

		return new SelfRegisterOAuthClientResult(clientId, rawSecret, null, null, null, null);
	}

	/**
	 * 게이트웨이 refresh 트리거 (실패 시 경고 로그만, 롤백 없음)
	 *
	 * <p>트랜잭션이 활성화된 경우 커밋 이후에 실행한다. 트랜잭션이 없으면 즉시 실행한다(단위 테스트 등).
	 */
	private void triggerRefresh() {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(
					new TransactionSynchronization() {
						@Override
						public void afterCommit() {
							doTriggerRefresh();
						}
					});
		} else {
			doTriggerRefresh();
		}
	}

	private void doTriggerRefresh() {
		try {
			gatewayRefreshClient.triggerRefresh();
		} catch (Exception e) {
			log.warn("Gateway refresh 트리거 실패: {}", e.getMessage());
		}
	}
}
