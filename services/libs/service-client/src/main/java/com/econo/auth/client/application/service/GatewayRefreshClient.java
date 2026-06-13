package com.econo.auth.client.application.service;

/**
 * api-gateway refresh 호출 추상화 포트
 *
 * <p>auth-api가 라우트 변경 시 api-gateway의 내부 refresh 엔드포인트를 호출하도록 트리거한다. 구현체는 auth-api 계층( {@code
 * com.econo.auth.api.application.service.GatewayRefreshClientImpl})에 위치한다.
 *
 * <p>refresh 실패 시 예외를 전파하지 않고 경고 로그만 남긴다 (최종 일관성 수용).
 */
public interface GatewayRefreshClient {

	/** api-gateway에 RefreshRoutesEvent 트리거 요청 */
	void triggerRefresh();
}
