package com.econo.auth.client.exception;

/** upstreamUrl SSRF 검증 실패 시 — 400 ROUTE_UPSTREAM_INVALID */
public class RouteUpstreamInvalidException extends RuntimeException {

	/**
	 * @param reason 검증 실패 사유
	 */
	public RouteUpstreamInvalidException(String reason) {
		super("허용되지 않는 업스트림 URL입니다. reason=" + reason);
	}
}
