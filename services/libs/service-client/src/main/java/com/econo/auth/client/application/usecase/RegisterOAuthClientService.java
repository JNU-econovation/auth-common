package com.econo.auth.client.application.usecase;

import com.econo.auth.client.application.port.out.SasClientRegistrar;
import com.econo.auth.client.application.port.out.ServiceClientRepository;
import com.econo.auth.client.domain.GrantType;
import com.econo.auth.client.domain.ServiceClient;
import com.econo.auth.client.exception.DuplicateClientNameException;
import com.econo.auth.client.exception.RedirectUriRequiredException;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OAuth 클라이언트 등록 서비스
 *
 * <p>프론트엔드/모바일 전용 — 항상 authorization_code (PKCE) 클라이언트로 등록한다.
 */
@Service
@RequiredArgsConstructor
public class RegisterOAuthClientService {

	private final SasClientRegistrar sasClientRegistrar;
	private final ServiceClientRepository serviceClientRepository;

	/**
	 * OAuth 클라이언트 등록 명령
	 *
	 * @param clientName 클라이언트 이름
	 * @param redirectUris 허용 redirect URI 목록 (필수)
	 */
	public record RegisterOAuthClientCommand(String clientName, Set<String> redirectUris) {}

	/**
	 * OAuth 클라이언트 등록 결과
	 *
	 * @param clientId 등록된 클라이언트 ID
	 */
	public record RegisterOAuthClientResult(String clientId) {}

	/**
	 * OAuth 클라이언트 등록 (authorization_code 고정)
	 *
	 * @throws RedirectUriRequiredException redirectUris가 없을 때
	 * @throws DuplicateClientNameException clientName 중복 시
	 */
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
		sasClientRegistrar.registerAuthorizationCodeClient(
				clientId, command.clientName(), command.redirectUris());
		serviceClientRepository.save(
				ServiceClient.create(clientId, command.clientName(), GrantType.AUTHORIZATION_CODE, null));

		return new RegisterOAuthClientResult(clientId);
	}
}
