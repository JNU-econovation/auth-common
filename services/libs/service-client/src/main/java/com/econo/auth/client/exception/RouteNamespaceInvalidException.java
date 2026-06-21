package com.econo.auth.client.exception;

/** pathPrefix가 /api/{namespace}/ 포맷을 따르지 않을 때 — 400 ROUTE_NAMESPACE_INVALID */
public class RouteNamespaceInvalidException extends RuntimeException {

	/**
	 * @param pathPrefix 포맷 위반 경로 접두사
	 */
	public RouteNamespaceInvalidException(String pathPrefix) {
		super("pathPrefix는 /api/{namespace}/ 형태여야 합니다. pathPrefix=" + pathPrefix);
	}
}
