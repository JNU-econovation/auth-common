package com.econo.auth.client.application.usecase;

import com.econo.auth.client.application.port.out.SasClientRegistrar;
import com.econo.auth.client.application.port.out.ServiceClientRepository;
import com.econo.auth.client.application.port.out.ServiceRouteRepository;
import com.econo.auth.client.domain.GrantType;
import com.econo.auth.client.domain.ServiceClient;
import com.econo.auth.client.domain.ServiceRoute;
import com.econo.auth.client.exception.DuplicateClientNameException;
import com.econo.auth.client.exception.RedirectUriRequiredException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.Nullable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OAuth 클라이언트 등록 서비스
 *
 * <p>SAS 인프라 의존은 {@link SasClientRegistrar} 포트 뒤로 격리되어 있다.
 *
 * <ul>
 *   <li>authorization_code: PKCE 필수 공개 클라이언트. clientSecret 미발급.
 *   <li>client_credentials: BCrypt secret 발급. apiKeyHash는 항상 null.
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class RegisterOAuthClientService {

	private static final int SECRET_BYTE_LENGTH = 32;

	private final SasClientRegistrar sasClientRegistrar;
	private final ServiceClientRepository serviceClientRepository;
	private final ServiceRouteRepository serviceRouteRepository;
	private final PasswordEncoder passwordEncoder;

	/**
	 * OAuth 클라이언트 등록 명령
	 *
	 * @param grantType 그랜트 타입. null이면 서비스에서 CLIENT_CREDENTIALS 디폴트 적용.
	 * @param clientName 클라이언트 이름
	 * @param redirectUris 리다이렉트 URI (authorization_code 전용)
	 * @param upstreamUrl 업스트림 서비스 URL (선택)
	 * @param pathPrefix 경로 접두사 (선택)
	 */
	public record RegisterOAuthClientCommand(
			@Nullable GrantType grantType,
			String clientName,
			Set<String> redirectUris,
			String upstreamUrl,
			String pathPrefix) {}

	/**
	 * OAuth 클라이언트 등록 결과
	 *
	 * @param clientId 등록된 클라이언트 ID
	 * @param clientSecret 원본 시크릿 (client_credentials 전용, 1회 반환)
	 * @param routeId 등록된 라우트 ID (upstreamUrl 있을 때만)
	 */
	public record RegisterOAuthClientResult(String clientId, String clientSecret, String routeId) {}

	/**
	 * OAuth 클라이언트 등록
	 *
	 * @param command 등록 명령
	 * @return 등록 결과
	 * @throws RedirectUriRequiredException authorization_code인데 redirectUris 없을 때
	 * @throws DuplicateClientNameException clientName 중복 시
	 */
	@Transactional
	public RegisterOAuthClientResult register(RegisterOAuthClientCommand command) {
		validateCommand(command);

		if (serviceClientRepository.existsByClientName(command.clientName())) {
			throw new DuplicateClientNameException(command.clientName());
		}

		String clientId = UUID.randomUUID().toString();
		String rawSecret = null;
		GrantType resolved =
				command.grantType() != null ? command.grantType() : GrantType.CLIENT_CREDENTIALS;

		if (resolved == GrantType.AUTHORIZATION_CODE) {
			if (command.redirectUris() == null || command.redirectUris().isEmpty()) {
				throw new RedirectUriRequiredException();
			}
			sasClientRegistrar.registerAuthorizationCodeClient(
					clientId, command.clientName(), command.redirectUris());
		} else {
			rawSecret = generateSecret();
			String bcryptSecret = "{bcrypt}" + passwordEncoder.encode(rawSecret);
			sasClientRegistrar.registerClientCredentialsClient(
					clientId, command.clientName(), bcryptSecret);
		}

		serviceClientRepository.save(
				ServiceClient.create(clientId, command.clientName(), resolved, null));

		String routeId = null;
		if (command.upstreamUrl() != null) {
			routeId = UUID.randomUUID().toString();
			serviceRouteRepository.save(
					new ServiceRoute(routeId, clientId, command.upstreamUrl(), command.pathPrefix()));
		}

		return new RegisterOAuthClientResult(clientId, rawSecret, routeId);
	}

	/**
	 * 등록된 라우트 목록 조회
	 *
	 * @return 라우트 목록
	 */
	public List<ServiceRoute> getRoutes() {
		return serviceRouteRepository.findAll();
	}

	private void validateCommand(RegisterOAuthClientCommand command) {
		if (command.clientName() == null) {
			throw new IllegalArgumentException("clientName은 필수입니다.");
		}
	}

	private String generateSecret() {
		byte[] bytes = new byte[SECRET_BYTE_LENGTH];
		new SecureRandom().nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}
