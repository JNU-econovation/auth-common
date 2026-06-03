package com.econo.auth.client.adapter.out.sas;

import com.econo.auth.client.application.port.out.SasRedirectUriManager;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.stereotype.Component;

/**
 * {@link SasRedirectUriManager} 구현체 — SAS {@link RegisteredClientRepository} 위임
 *
 * <p>RegisteredClient 조회·수정 로직을 인프라 어댑터에 격리하므로 Application 계층은 SAS API를 알 필요가 없다.
 */
@Component
@RequiredArgsConstructor
public class SasRedirectUriManagerAdapter implements SasRedirectUriManager {

	private final RegisteredClientRepository registeredClientRepository;

	@Override
	public String findClientNameByClientId(String clientId) {
		RegisteredClient client = registeredClientRepository.findByClientId(clientId);
		if (client == null) {
			return null;
		}
		return client.getClientName();
	}

	@Override
	public Set<String> findRedirectUrisByClientId(String clientId) {
		RegisteredClient client = registeredClientRepository.findByClientId(clientId);
		if (client == null) {
			return null;
		}
		return client.getRedirectUris();
	}

	@Override
	public void updateRedirectUris(String clientId, Set<String> newUris) {
		RegisteredClient client = registeredClientRepository.findByClientId(clientId);
		if (client == null) {
			return;
		}
		RegisteredClient.Builder builder =
				RegisteredClient.from(client)
						.clientSettings(
								ClientSettings.withSettings(client.getClientSettings().getSettings()).build());
		builder.redirectUris(
				uris -> {
					uris.clear();
					uris.addAll(newUris);
				});
		registeredClientRepository.save(builder.build());
	}
}
