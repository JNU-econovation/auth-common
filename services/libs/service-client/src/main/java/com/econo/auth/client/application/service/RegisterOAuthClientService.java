package com.econo.auth.client.application.service;

import com.econo.auth.client.application.domain.GrantType;
import com.econo.auth.client.application.domain.ServiceClient;
import com.econo.auth.client.application.repository.SasClientRegistrar;
import com.econo.auth.client.application.repository.ServiceClientRepository;
import com.econo.auth.client.application.usecase.RegisterOAuthClientUseCase;
import com.econo.auth.client.exception.ClientLimitExceededException;
import com.econo.auth.client.exception.DuplicateClientNameException;
import com.econo.auth.client.exception.RedirectUriRequiredException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OAuth 클라이언트 등록 서비스
 *
 * <p>프론트엔드/모바일 전용 — 항상 authorization_code (PKCE) 클라이언트로 등록한다.
 */
@Service
@RequiredArgsConstructor
public class RegisterOAuthClientService implements RegisterOAuthClientUseCase {

	private final SasClientRegistrar sasClientRegistrar;
	private final ServiceClientRepository serviceClientRepository;
	private final PasswordEncoder passwordEncoder;

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
	 * @param command 셀프 등록 명령 (clientName, redirectUris, ownerId)
	 * @return 등록된 clientId와 1회 노출 clientSecret
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

		return new SelfRegisterOAuthClientResult(clientId, rawSecret);
	}
}
