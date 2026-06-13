package com.econo.auth.client.exception;

/** routeId에 해당하는 라우트를 찾을 수 없을 때 — 404 ROUTE_NOT_FOUND */
public class RouteNotFoundException extends RuntimeException {

	/**
	 * @param routeId 존재하지 않는 라우트 ID
	 */
	public RouteNotFoundException(String routeId) {
		super("라우트를 찾을 수 없습니다. routeId=" + routeId);
	}
}
