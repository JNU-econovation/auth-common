package com.econo.auth.gateway.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 게이트웨이 기동 완료 후 동적 라우트 초기 로드
 *
 * <p>{@link DynamicRouteConfig}에서 분리한 이유: 동일 {@code @Configuration}이 자신이 {@code @Bean}으로 만드는 {@link
 * DynamicRouteDefinitionRepository}를 필드 주입하면 자기참조 순환 의존이 되어 컨텍스트 기동이 실패한다. 별도 {@code @Component}로
 * 분리하여 생성자 주입으로 순환을 끊는다.
 *
 * <p>auth-api 미기동 시에도 {@link AuthApiRouteClient#fetchEnabledRoutes()}가 예외를 흡수하므로 게이트웨이 기동이 막히지 않으며,
 * 이후 refresh 엔드포인트로 캐시를 채울 수 있다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DynamicRouteInitializer {

	private final DynamicRouteDefinitionRepository dynamicRouteDefinitionRepository;
	private final ApplicationEventPublisher eventPublisher;

	/** 기동 완료 후 동적 라우트 초기 로드 + RefreshRoutesEvent 발행 (논블로킹 구독) */
	@EventListener(ApplicationReadyEvent.class)
	public void onApplicationReady() {
		log.info("기동 완료 후 동적 라우트 초기 로드 시작");
		dynamicRouteDefinitionRepository
				.reload()
				.doOnSuccess(
						v -> {
							eventPublisher.publishEvent(new RefreshRoutesEvent(this));
							log.info("동적 라우트 초기 로드 완료");
						})
				.doOnError(
						e -> log.warn("동적 라우트 초기 로드 실패 — 빈 캐시로 계속 기동, 이후 refresh로 채울 수 있음: {}", e.getMessage()))
				.subscribe();
	}
}
