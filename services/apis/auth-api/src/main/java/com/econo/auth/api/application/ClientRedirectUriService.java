package com.econo.auth.api.application;

import com.econo.auth.api.application.port.out.ServiceClientRepository;
import com.econo.auth.api.exception.InvalidClientException;
import com.econo.auth.api.exception.RedirectUriRequiredException;
import java.net.URI;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.stereotype.Service;

/**
 * OAuth 클라이언트의 redirectUri 관리 서비스
 *
 * <p>SAS {@link RegisteredClientRepository}를 통해 redirectUri를 추가·삭제·교체한다. CORS 허용 오리진은 redirectUri에서
 * scheme+host 기준으로 자동 추출한다.
 */
@Service
@RequiredArgsConstructor
public class ClientRedirectUriService {

	private static final int MAX_REDIRECT_URIS = 10;
	private static final int MAX_URI_LENGTH = 512;

	private final RegisteredClientRepository registeredClientRepository;
	private final ServiceClientRepository serviceClientRepository;

	/** clientId로 클라이언트 조회 */
	public RegisteredClient findByClientId(String clientId) {
		RegisteredClient client = registeredClientRepository.findByClientId(clientId);
		if (client == null) {
			throw new InvalidClientException();
		}
		return client;
	}

	/** redirectUri 추가 */
	public Set<String> addRedirectUri(String clientId, String newUri) {
		validate(newUri);
		RegisteredClient client = findByClientId(clientId);
		Set<String> current = new HashSet<>(client.getRedirectUris());
		if (current.size() >= MAX_REDIRECT_URIS) {
			throw new RedirectUriRequiredException();
		}
		current.add(newUri);
		save(client, current);
		return current;
	}

	/** redirectUri 제거 */
	public Set<String> removeRedirectUri(String clientId, String uri) {
		RegisteredClient client = findByClientId(clientId);
		Set<String> current = new HashSet<>(client.getRedirectUris());
		if (!current.remove(uri)) {
			throw new RedirectUriRequiredException();
		}
		if (current.isEmpty()) {
			throw new RedirectUriRequiredException();
		}
		save(client, current);
		return current;
	}

	/** redirectUri 전체 교체 */
	public Set<String> replaceRedirectUris(String clientId, Set<String> uris) {
		if (uris == null || uris.isEmpty() || uris.size() > MAX_REDIRECT_URIS) {
			throw new RedirectUriRequiredException();
		}
		uris.forEach(this::validate);
		RegisteredClient client = findByClientId(clientId);
		save(client, uris);
		return uris;
	}

	/**
	 * 등록된 모든 클라이언트의 redirectUri에서 CORS 허용 오리진 추출
	 *
	 * <p>DB에 저장된 모든 클라이언트의 redirectUri 오리진과 additionalOrigins를 합산하여 반환한다. Gateway의 CORS 설정에 활용된다.
	 *
	 * @param additionalOrigins 환경변수로 추가 등록된 오리진 목록
	 * @return 허용 오리진 목록
	 */
	public Set<String> extractAllowedOrigins(Set<String> additionalOrigins) {
		Set<String> origins = new HashSet<>(additionalOrigins);
		serviceClientRepository.findAllRegisteredClientIds().stream()
				.map(
						id -> {
							try {
								return registeredClientRepository.findByClientId(id);
							} catch (Exception e) {
								return null;
							}
						})
				.filter(Objects::nonNull)
				.flatMap(client -> client.getRedirectUris().stream())
				.map(ClientRedirectUriService::extractOrigin)
				.filter(Objects::nonNull)
				.forEach(origins::add);
		return origins;
	}

	/** redirectUri에서 오리진(scheme+host+port) 추출 */
	public static String extractOrigin(String uri) {
		try {
			URI u = URI.create(uri);
			String origin = u.getScheme() + "://" + u.getHost();
			if (u.getPort() != -1) {
				origin += ":" + u.getPort();
			}
			return origin;
		} catch (Exception e) {
			return null;
		}
	}

	private void validate(String uri) {
		if (uri == null || uri.isBlank() || uri.length() > MAX_URI_LENGTH) {
			throw new RedirectUriRequiredException();
		}
		try {
			URI u = URI.create(uri);
			String scheme = u.getScheme();
			if (scheme == null || (!scheme.equals("https") && !scheme.equals("http"))) {
				throw new RedirectUriRequiredException();
			}
		} catch (IllegalArgumentException e) {
			throw new RedirectUriRequiredException();
		}
	}

	private void save(RegisteredClient client, Set<String> newUris) {
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
