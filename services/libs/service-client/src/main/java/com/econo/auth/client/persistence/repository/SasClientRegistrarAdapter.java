package com.econo.auth.client.persistence.repository;

import com.econo.auth.client.application.repository.SasClientRegistrar;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.stereotype.Component;

/**
 * {@link SasClientRegistrar} 구현체 — SAS {@link RegisteredClientRepository} 위임
 *
 * <p>RegisteredClient 객체 빌드 로직이 인프라 어댑터에만 존재하므로 Application 계층은 SAS API를 알 필요가 없다.
 */
@Component
@RequiredArgsConstructor
public class SasClientRegistrarAdapter implements SasClientRegistrar {

	private final RegisteredClientRepository registeredClientRepository;

	// SAS 1.x 기준: 테이블명 oauth2_registered_client, 컬럼명 client_id (VARCHAR).
	// SAS 버전 업그레이드 시 이 쿼리 검증 필요.
	private final JdbcTemplate jdbcTemplate;

	@Override
	public void registerAuthorizationCodeClient(
			String clientId, String clientName, Set<String> redirectUris) {
		RegisteredClient.Builder builder =
				RegisteredClient.withId(UUID.randomUUID().toString())
						.clientId(clientId)
						.clientName(clientName)
						.clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
						.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
						.scope(OidcScopes.OPENID)
						.clientSettings(
								ClientSettings.builder()
										.requireProofKey(true)
										.requireAuthorizationConsent(false)
										.build());
		redirectUris.forEach(builder::redirectUri);
		registeredClientRepository.save(builder.build());
	}

	/**
	 * SAS oauth2_registered_client 하드 삭제
	 *
	 * <p>SAS {@code RegisteredClientRepository}는 표준 delete 메서드를 제공하지 않으므로 JdbcTemplate으로 직접 DELETE한다.
	 * SAS 1.x 기준: 테이블명 oauth2_registered_client, 컬럼명 client_id.
	 *
	 * @param clientId 삭제할 클라이언트 ID
	 */
	@Override
	public void unregisterClient(String clientId) {
		// SAS 1.x: oauth2_registered_client.client_id (VARCHAR)
		jdbcTemplate.update("DELETE FROM oauth2_registered_client WHERE client_id = ?", clientId);
	}

	/**
	 * SAS oauth2_registered_client 클라이언트 이름 수정
	 *
	 * <p>기존 {@code RegisteredClient}를 조회하여 clientName만 교체 후 save로 덮어쓴다.
	 *
	 * @param clientId 수정할 클라이언트 ID
	 * @param newName 새 클라이언트 이름
	 */
	@Override
	public void updateClientName(String clientId, String newName) {
		RegisteredClient existing = registeredClientRepository.findByClientId(clientId);
		if (existing == null) {
			return;
		}
		RegisteredClient updated = RegisteredClient.from(existing).clientName(newName).build();
		registeredClientRepository.save(updated);
	}
}
