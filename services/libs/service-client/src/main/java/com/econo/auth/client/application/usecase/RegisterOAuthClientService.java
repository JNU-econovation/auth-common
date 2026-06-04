package com.econo.auth.api.application.usecase;

import com.econo.auth.api.application.port.out.SasClientRegistrar;
import com.econo.auth.api.application.port.out.ServiceClientRepository;
import com.econo.auth.api.domain.GrantType;
import com.econo.auth.api.domain.ServiceClient;
import com.econo.auth.api.exception.DuplicateClientNameException;
import com.econo.auth.api.exception.RedirectUriRequiredException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OAuth 클라이언트 등록 서비스
 *
 * <p>SAS 인프라 의존은 {@link SasClientRegistrar} 포트 뒤로 격리되어 있다.
 *
 * <ul>
 *   <li>authorization_code: PKCE 필수 공개 클라이언트
 *   <li>client_credentials: BCrypt secret, SHA-256 api_key_hash
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class RegisterOAuthClientService {

	private static final int SECRET_BYTE_LENGTH = 32;

	private final SasClientRegistrar sasClientRegistrar;
	private final ServiceClientRepository serviceClientRepository;
	private final PasswordEncoder passwordEncoder;

	/**
	 * OAuth 클라이언트 등록 명령
	 *
	 * @param grantType 그랜트 타입
	 * @param clientName 클라이언트 이름
	 * @param redirectUris 리다이렉트 URI (authorization_code 전용)
	 */
	public record RegisterOAuthClientCommand(
			GrantType grantType, String clientName, Set<String> redirectUris) {}

	/**
	 * OAuth 클라이언트 등록 결과
	 *
	 * @param clientId 등록된 클라이언트 ID
	 * @param clientSecret 원본 시크릿 (client_credentials 전용, 1회 반환)
	 */
	public record RegisterOAuthClientResult(String clientId, String clientSecret) {}

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
		String apiKeyHash = null;

		if (command.grantType() == GrantType.AUTHORIZATION_CODE) {
			if (command.redirectUris() == null || command.redirectUris().isEmpty()) {
				throw new RedirectUriRequiredException();
			}
			sasClientRegistrar.registerAuthorizationCodeClient(
					clientId, command.clientName(), command.redirectUris());
		} else {
			rawSecret = generateSecret();
			apiKeyHash = sha256Hex(rawSecret);
			String bcryptSecret = "{bcrypt}" + passwordEncoder.encode(rawSecret);
			sasClientRegistrar.registerClientCredentialsClient(
					clientId, command.clientName(), bcryptSecret);
		}

		serviceClientRepository.save(
				ServiceClient.create(clientId, command.clientName(), command.grantType(), apiKeyHash));

		return new RegisterOAuthClientResult(clientId, rawSecret);
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
