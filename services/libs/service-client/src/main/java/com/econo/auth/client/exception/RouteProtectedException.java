package com.econo.auth.client.exception;

/** 보호 경로를 가로채거나 삭제하려 할 때 — 403 ROUTE_PROTECTED */
public class RouteProtectedException extends RuntimeException {

	/**
	 * @param pathPrefix 보호 경로 접두사
	 */
	public RouteProtectedException(String pathPrefix) {
		super("보호 경로에 대한 작업은 허용되지 않습니다. pathPrefix=" + pathPrefix);
	}
}
