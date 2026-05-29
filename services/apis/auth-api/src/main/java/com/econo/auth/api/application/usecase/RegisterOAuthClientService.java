package com.econo.auth.api.application.usecase;

import com.econo.auth.api.application.port.out.ServiceClientRepository;
import com.econo.auth.api.application.port.out.ServiceRouteRepository;
import com.econo.auth.api.domain.GrantType;
import com.econo.auth.api.domain.ServiceClient;
import com.econo.auth.api.domain.ServiceRoute;
import com.econo.auth.api.exception.DuplicateClientNameException;
import com.econo.auth.api.exception.RedirectUriRequiredException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OAuth 클라이언트 등록 서비스
 *
 * <p>authorization_code: PKCE 필수, ClientAuthenticationMethod.NONE client_credentials: BCrypt
 * secret, SHA-256 api_key_hash
 */
@Service
@RequiredArgsConstructor
public class RegisterOAuthClientService {

	private static final int SECRET_BYTE_LENGTH = 32;
	private final RegisteredClientRepository sasRegisteredClientRepository;
	private final ServiceClientRepository serviceClientRepository;
	private final ServiceRouteRepository serviceRouteRepository;
	private final PasswordEncoder passwordEncoder;

	/**
	 * OAuth 클라이언트 등록 명령
	 *
	 * @param grantType 그랜트 타입
	 * @param clientName 클라이언트 이름
	 * @param redirectUris 리다이렉트 URI (authorization_code 전용)
	 * @param upstreamUrl 업스트림 서비스 URL (선택)
	 * @param pathPrefix 경로 접두사 (선택)
	 */
	public record RegisterOAuthClientCommand(
			GrantType grantType,
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
	 */
	@Transactional
	public RegisterOAuthClientResult register(RegisterOAuthClientCommand command) {
		validateCommand(command);

		if (serviceClientRepository.existsByClientName(command.clientName())) {
			throw new DuplicateClientNameException(command.clientName());
		}

		String clientId = UUID.randomUUID().toString();
		String rawSecret = null;
		String apiKeyHash = null;

		RegisteredClient registeredClient;

		if (command.grantType() == GrantType.AUTHORIZATION_CODE) {
			if (command.redirectUris() == null || command.redirectUris().isEmpty()) {
				throw new RedirectUriRequiredException();
			}

			RegisteredClient.Builder builder =
					RegisteredClient.withId(UUID.randomUUID().toString())
							.clientId(clientId)
							.clientName(command.clientName())
							.clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
							.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
							.scope(OidcScopes.OPENID)
							.clientSettings(
									ClientSettings.builder()
											.requireProofKey(true)
											.requireAuthorizationConsent(false)
											.build());

			for (String uri : command.redirectUris()) {
				builder.redirectUri(uri);
			}

			registeredClient = builder.build();

		} else {
			// CLIENT_CREDENTIALS
			rawSecret = generateSecret();
			String hashedSecret = "{bcrypt}" + passwordEncoder.encode(rawSecret);
			apiKeyHash = sha256Hex(rawSecret);

			registeredClient =
					RegisteredClient.withId(UUID.randomUUID().toString())
							.clientId(clientId)
							.clientName(command.clientName())
							.clientSecret(hashedSecret)
							.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
							.authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
							.scope("read")
							.build();
		}

		sasRegisteredClientRepository.save(registeredClient);

		ServiceClient serviceClient =
				ServiceClient.create(clientId, command.clientName(), command.grantType(), apiKeyHash);
		serviceClientRepository.save(serviceClient);

		String routeId = null;
		if (command.upstreamUrl() != null) {
			routeId = UUID.randomUUID().toString();
			ServiceRoute serviceRoute =
					new ServiceRoute(routeId, clientId, command.upstreamUrl(), command.pathPrefix());
			serviceRouteRepository.save(serviceRoute);
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
		if (command.grantType() == null) {
			throw new IllegalArgumentException("grantType은 필수입니다.");
		}
		if (command.clientName() == null) {
			throw new IllegalArgumentException("clientName은 필수입니다.");
		}
	}

	private String generateSecret() {
		byte[] bytes = new byte[SECRET_BYTE_LENGTH];
		new SecureRandom().nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private String sha256Hex(String input) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
		}
	}
}
