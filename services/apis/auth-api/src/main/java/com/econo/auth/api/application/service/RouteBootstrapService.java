package com.econo.auth.api.application.service;

import com.econo.auth.client.application.domain.ServiceRoute;
import com.econo.auth.client.application.repository.ServiceRouteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/**
 * auth-api 기동 시 시드 라우트 멱등 INSERT 서비스
 *
 * <p>정적 보호 경로(auth-api 핵심 경로)는 {@link com.econo.auth.gateway.config.GatewayRoutingConfig}의 정적 라우트로
 * 유지하므로, 실제 시드가 필요한 경우에만 사용한다. 현재는 부트스트랩 유효성 검사 역할로만 동작한다.
 */
@Slf4j
@RequiredArgsConstructor
public class RouteBootstrapService {

	private final ServiceRouteRepository serviceRouteRepository;

	@Value("${AUTH_API_URI:http://localhost:8081}")
	private String authApiUri;

	/**
	 * 애플리케이션 준비 완료 후 실행 — 현재 설계에서 정적 보호 경로는 GatewayRoutingConfig에 고정이므로 실제 seed INSERT를 하지 않는다. 필요 시
	 * seedIfAbsent()를 활성화한다.
	 */
	@EventListener(ApplicationReadyEvent.class)
	public void bootstrap() {
		log.info("RouteBootstrapService: 기동 완료. 동적 라우트 시드는 AdminRouteController를 통해 등록하세요.");
	}

	/**
	 * pathPrefix가 없으면 시드 INSERT (멱등)
	 *
	 * @param pathPrefix 경로 접두사
	 * @param upstreamUrl 업스트림 서비스 URL
	 */
	private void seedIfAbsent(String pathPrefix, String upstreamUrl) {
		if (!serviceRouteRepository.existsByPathPrefix(pathPrefix)) {
			ServiceRoute route = ServiceRoute.create(pathPrefix, upstreamUrl, true);
			serviceRouteRepository.save(route);
			log.info(
					"RouteBootstrapService: 시드 라우트 등록 완료. pathPrefix={}, upstreamUrl={}",
					pathPrefix,
					upstreamUrl);
		}
	}
}
