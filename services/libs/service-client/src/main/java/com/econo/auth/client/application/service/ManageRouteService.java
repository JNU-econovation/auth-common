package com.econo.auth.client.application.service;

import com.econo.auth.client.application.domain.ServiceRoute;
import com.econo.auth.client.application.repository.ServiceRouteRepository;
import com.econo.auth.client.application.usecase.ManageRouteUseCase;
import com.econo.auth.client.exception.RouteNotFoundException;
import com.econo.auth.client.exception.RoutePathConflictException;
import com.econo.auth.client.exception.RouteProtectedException;
import com.econo.auth.client.exception.RouteUpstreamInvalidException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

/**
 * 라우트 CRUD 유스케이스 구현체
 *
 * <p>upstreamUrl SSRF 검증, pathPrefix 충돌·보호경로 검증, DB 저장, 게이트웨이 refresh 트리거를 순서대로 실행한다.
 *
 * <p>ApplicationServiceConfig에서 @Bean 등록 (service-client 내부 @Service 사용 안 함).
 */
@Slf4j
@RequiredArgsConstructor
public class ManageRouteService implements ManageRouteUseCase {

	private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

	private final ServiceRouteRepository serviceRouteRepository;
	private final GatewayRefreshClient gatewayRefreshClient;
	private final ProtectedPathPolicy protectedPathPolicy;

	/**
	 * 라우트 생성
	 *
	 * @param command 생성 명령
	 * @return 생성된 라우트 결과
	 */
	@Override
	@Transactional
	public RouteResult createRoute(CreateRouteCommand command) {
		validateUpstreamUrl(command.upstreamUrl());
		validatePathPrefix(command.pathPrefix());

		ServiceRoute route =
				ServiceRoute.create(command.pathPrefix(), command.upstreamUrl(), command.enabled());
		ServiceRoute saved = serviceRouteRepository.save(route);

		triggerRefresh();

		return toResult(saved);
	}

	/**
	 * 라우트 수정
	 *
	 * @param routeId 수정할 라우트 UUID 문자열
	 * @param command 수정 명령
	 * @return 수정된 라우트 결과
	 */
	@Override
	@Transactional
	public RouteResult updateRoute(String routeId, UpdateRouteCommand command) {
		ServiceRoute existing =
				serviceRouteRepository
						.findById(routeId)
						.orElseThrow(() -> new RouteNotFoundException(routeId));

		// 기존 라우트의 pathPrefix가 보호경로이면 upstream 변조를 방지한다
		if (protectedPathPolicy.isProtected(existing.pathPrefix())) {
			throw new RouteProtectedException(existing.pathPrefix());
		}

		validateUpstreamUrl(command.upstreamUrl());
		validatePathPrefixForUpdate(command.pathPrefix(), routeId);

		ServiceRoute updated =
				new ServiceRoute(
						routeId,
						command.pathPrefix(),
						command.upstreamUrl(),
						command.enabled(),
						existing.createdAt(),
						null);
		ServiceRoute saved = serviceRouteRepository.save(updated);

		triggerRefresh();

		return toResult(saved);
	}

	/**
	 * 라우트 삭제
	 *
	 * @param routeId 삭제할 라우트 UUID 문자열
	 */
	@Override
	@Transactional
	public void deleteRoute(String routeId) {
		ServiceRoute route =
				serviceRouteRepository
						.findById(routeId)
						.orElseThrow(() -> new RouteNotFoundException(routeId));

		if (protectedPathPolicy.isProtected(route.pathPrefix())) {
			throw new RouteProtectedException(route.pathPrefix());
		}

		serviceRouteRepository.deleteById(routeId);

		triggerRefresh();
	}

	/**
	 * 전체 라우트 목록 조회
	 *
	 * @return 전체 라우트 결과 목록
	 */
	@Override
	@Transactional(readOnly = true)
	public List<RouteResult> listRoutes() {
		return serviceRouteRepository.findAll().stream().map(this::toResult).toList();
	}

	/**
	 * enabled=true 라우트 목록 조회 (DB 레벨 필터 — V10 인덱스 활용)
	 *
	 * @return 활성화된 라우트 결과 목록
	 */
	@Override
	@Transactional(readOnly = true)
	public List<RouteResult> listEnabledRoutes() {
		return serviceRouteRepository.findAllEnabled().stream().map(this::toResult).toList();
	}

	/**
	 * 단건 라우트 조회
	 *
	 * @param routeId 라우트 UUID 문자열
	 * @return 라우트 결과
	 */
	@Override
	@Transactional(readOnly = true)
	public RouteResult getRoute(String routeId) {
		return serviceRouteRepository
				.findById(routeId)
				.map(this::toResult)
				.orElseThrow(() -> new RouteNotFoundException(routeId));
	}

	/**
	 * upstreamUrl SSRF 검증
	 *
	 * <p>허용 스킴: http, https. 호스트 필수. private IP 및 localhost 차단.
	 *
	 * @param url 검증할 URL
	 * @throws RouteUpstreamInvalidException 검증 실패 시
	 */
	private void validateUpstreamUrl(String url) {
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
			// 운영 환경은 내부망 전제이므로 DNS에 아직 등록되지 않은 컨테이너 hostname 등이 올 수 있다.
			// 명시적 IP 형식(예: 10.0.0.1)은 InetAddress.getByName()이 DNS 없이 직접 파싱하므로 위 검사를 통과한다.
			// DNS rebinding 공격(등록 시점과 실제 요청 시점의 응답 불일치)은 이 방식으로 완전히 방어하기 어려우나,
			// 내부망 전제 운영 환경에서는 수용 가능한 트레이드오프로 판단한다.
			log.debug("hostname DNS 조회 실패 (내부 서비스 hostname으로 허용): host={}", host);
		}
	}

	/**
	 * private IP, loopback, 와일드카드 주소 여부 확인
	 *
	 * <p>0.0.0.0(IPv4) / ::(IPv6) 같은 와일드카드 주소({@code isAnyLocalAddress()})도 SSRF 벡터가 될 수 있으므로 차단한다.
	 * loopback, site-local(RFC 1918 private), link-local(169.254.x.x / fe80::) 도 차단한다.
	 *
	 * <p><b>UnknownHostException 정책</b>: DNS 조회 실패는 내부망 전제 운영 환경에서 정상 hostname일 수 있으므로 허용한다 (위 {@link
	 * #validateUpstreamUrl} 참조). 단, 등록 시점 검증이므로 DNS rebinding 공격(나중에 응답이 달라지는 경우)에 대한 방어는 이 검사만으로는
	 * 불충분하다 — 운영 내부망 전제로 수용한다.
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
		// 0.0.0.0 / :: 같은 와일드카드 주소 차단
		if (address.isAnyLocalAddress()) {
			return true;
		}
		return false;
	}

	/**
	 * pathPrefix 검증 (신규 등록 시)
	 *
	 * @param pathPrefix 검사할 경로 접두사
	 * @throws RouteProtectedException 보호 경로에 해당할 때
	 * @throws RoutePathConflictException pathPrefix가 이미 존재할 때
	 */
	private void validatePathPrefix(String pathPrefix) {
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
	private void validatePathPrefixForUpdate(String pathPrefix, String routeId) {
		if (protectedPathPolicy.isProtected(pathPrefix)) {
			throw new RouteProtectedException(pathPrefix);
		}

		if (serviceRouteRepository.existsByPathPrefixAndRouteIdNot(pathPrefix, routeId)) {
			throw new RoutePathConflictException(pathPrefix);
		}
	}

	/** 게이트웨이 refresh 트리거 (실패 시 경고 로그만, 롤백 없음) */
	private void triggerRefresh() {
		try {
			gatewayRefreshClient.triggerRefresh();
		} catch (Exception e) {
			log.warn("Gateway refresh 트리거 실패 (최종 일관성 수용): {}", e.getMessage());
		}
	}

	private RouteResult toResult(ServiceRoute route) {
		return new RouteResult(
				route.routeId(),
				route.pathPrefix(),
				route.upstreamUrl(),
				route.enabled(),
				route.createdAt(),
				route.updatedAt());
	}
}
