package com.econo.auth.client.application.service;

import com.econo.auth.client.application.repository.ServiceRouteRepository;
import com.econo.auth.client.exception.RoutePathConflictException;
import com.econo.auth.client.exception.RouteProtectedException;
import com.econo.auth.client.exception.RouteUpstreamInvalidException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * SSRF 검증·보호경로·pathPrefix 중복 검증 공유 유틸 클래스.
 *
 * <p>{@link ManageRouteService}와 {@link RegisterOAuthClientService}가 함께 사용한다. {@code
 * ApplicationServiceConfig}에서 {@code @Bean} 수동 등록.
 */
@Slf4j
@RequiredArgsConstructor
public class RouteValidator {

	private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

	private final ServiceRouteRepository serviceRouteRepository;
	private final ProtectedPathPolicy protectedPathPolicy;

	/**
	 * upstreamUrl SSRF 검증
	 *
	 * <p>허용 스킴: http, https. 호스트 필수. private IP 및 localhost 차단.
	 *
	 * @param url 검증할 URL
	 * @throws RouteUpstreamInvalidException 검증 실패 시
	 */
	public void validateUpstreamUrl(String url) {
		if (url == null || url.isBlank()) {
			throw new RouteUpstreamInvalidException("upstreamUrl이 비어있습니다.");
		}

		URI uri;
		try {
			uri = new URI(url);
		} catch (URISyntaxException e) {
			throw new RouteUpstreamInvalidException("유효하지 않은 URL 형식입니다. url=" + url);
		}

		String scheme = uri.getScheme();
		if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase())) {
			throw new RouteUpstreamInvalidException("허용되지 않는 스킴입니다. scheme=" + scheme);
		}

		String host = uri.getHost();
		if (host == null || host.isBlank()) {
			throw new RouteUpstreamInvalidException("호스트가 비어있습니다.");
		}

		if ("localhost".equalsIgnoreCase(host)) {
			throw new RouteUpstreamInvalidException("localhost는 허용되지 않습니다.");
		}

		// IP 주소인 경우 private IP 차단
		try {
			InetAddress address = InetAddress.getByName(host);
			if (isPrivateOrLoopback(address)) {
				throw new RouteUpstreamInvalidException(
						"private IP 또는 loopback 주소는 허용되지 않습니다. host=" + host);
			}
		} catch (UnknownHostException e) {
			// [의도적 결정] hostname DNS 조회 실패는 내부 서비스 hostname으로 허용한다.
			log.debug("hostname DNS 조회 실패 (내부 서비스 hostname으로 허용): host={}", host);
		}
	}

	/**
	 * pathPrefix가 보호 경로에 해당하는지 확인 (ProtectedPathPolicy 위임)
	 *
	 * @param pathPrefix 검사할 경로 접두사
	 * @return 보호 경로이면 true
	 */
	public boolean isProtected(String pathPrefix) {
		return protectedPathPolicy.isProtected(pathPrefix);
	}

	/**
	 * pathPrefix 검증 (신규 등록 시)
	 *
	 * @param pathPrefix 검사할 경로 접두사
	 * @throws RouteProtectedException 보호 경로에 해당할 때
	 * @throws RoutePathConflictException pathPrefix가 이미 존재할 때
	 */
	public void validatePathPrefix(String pathPrefix) {
		if (protectedPathPolicy.isProtected(pathPrefix)) {
			throw new RouteProtectedException(pathPrefix);
		}

		if (serviceRouteRepository.existsByPathPrefix(pathPrefix)) {
			throw new RoutePathConflictException(pathPrefix);
		}
	}

	/**
	 * pathPrefix 검증 (수정 시 자기 자신 제외)
	 *
	 * @param pathPrefix 검사할 경로 접두사
	 * @param routeId 수정 대상 라우트 UUID (자기 자신 제외용)
	 * @throws RouteProtectedException 보호 경로에 해당할 때
	 * @throws RoutePathConflictException 다른 라우트와 pathPrefix가 충돌할 때
	 */
	public void validatePathPrefixForUpdate(String pathPrefix, String routeId) {
		if (protectedPathPolicy.isProtected(pathPrefix)) {
			throw new RouteProtectedException(pathPrefix);
		}

		if (serviceRouteRepository.existsByPathPrefixAndRouteIdNot(pathPrefix, routeId)) {
			throw new RoutePathConflictException(pathPrefix);
		}
	}

	/**
	 * private IP, loopback, 와일드카드 주소 여부 확인
	 *
	 * @param address 검사할 InetAddress
	 * @return 차단 대상이면 true
	 */
	private boolean isPrivateOrLoopback(InetAddress address) {
		if (address.isLoopbackAddress()) {
			return true;
		}
		if (address.isSiteLocalAddress()) {
			return true;
		}
		if (address.isLinkLocalAddress()) {
			return true;
		}
		if (address.isAnyLocalAddress()) {
			return true;
		}
		return false;
	}
}
