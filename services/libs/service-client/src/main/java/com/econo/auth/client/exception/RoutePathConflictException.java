package com.econo.auth.client.exception;

/** 등록·수정 시 pathPrefix가 다른 라우트와 중복될 때 — 409 ROUTE_PATH_CONFLICT */
public class RoutePathConflictException extends RuntimeException {

	/**
	 * @param pathPrefix 충돌하는 경로 접두사
	 */
	public RoutePathConflictException(String pathPrefix) {
		super("이미 등록된 경로 접두사입니다. pathPrefix=" + pathPrefix);
	}
}
