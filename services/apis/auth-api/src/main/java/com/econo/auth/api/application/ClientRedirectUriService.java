package com.econo.auth.api.application;

import com.econo.auth.api.exception.InvalidClientException;
import com.econo.auth.api.exception.RedirectUriRequiredException;
import java.net.URI;
import java.util.HashSet;
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

	/** 등록된 모든 클라이언트의 redirectUri에서 CORS 허용 오리진 추출 */
	public Set<String> extractAllowedOrigins(Set<String> additionalOrigins) {
		Set<String> origins = new HashSet<>(additionalOrigins);
		// RegisteredClientRepository에 전체 조회 API가 없으므로 DB에서 직접 조회는 별도 구현 필요
		// 현재는 additionalOrigins(env)만 반환
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
		// 기존 redirectUri 모두 제거 후 새로 추가
		builder.redirectUris(
				uris -> {
					uris.clear();
					uris.addAll(newUris);
				});
		registeredClientRepository.save(builder.build());
	}
}
