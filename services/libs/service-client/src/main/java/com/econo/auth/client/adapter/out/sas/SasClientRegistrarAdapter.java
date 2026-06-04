package com.econo.auth.client.adapter.out.sas;

import com.econo.auth.client.application.port.out.SasClientRegistrar;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
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
}
