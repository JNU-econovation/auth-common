package com.econo.auth.gateway.presentation.controller;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * 내부 refresh 엔드포인트 라우터
 *
 * <p>{@code POST /api/v1/internal/routes/refresh}를 {@link RouteRefreshHandler}에 연결한다. 이 경로는 Spring
 * Cloud Gateway 라우팅 테이블({@code service_route})에 등록되지 않으므로 외부에서 접근 불가.
 */
@Configuration
public class InternalRouteRefreshRouter {

	/**
	 * RouterFunction 빈 등록
	 *
	 * @param handler RouteRefreshHandler
	 * @return RouterFunction 인스턴스
	 */
	@Bean
	public RouterFunction<ServerResponse> internalRefreshRoute(RouteRefreshHandler handler) {
		return RouterFunctions.route(
				RequestPredicates.POST("/api/v1/internal/routes/refresh"), handler::handle);
	}
}
