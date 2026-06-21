package com.econo.auth.client.exception;

/** 네임스페이스를 다른 owner가 이미 선점했을 때 — 403 ROUTE_NAMESPACE_TAKEN */
public class RouteNamespaceTakenException extends RuntimeException {

	/**
	 * @param namespace 이미 선점된 네임스페이스
	 */
	public RouteNamespaceTakenException(String namespace) {
		super("네임스페이스가 이미 다른 회원에 의해 점유되었습니다. namespace=" + namespace);
	}
}
