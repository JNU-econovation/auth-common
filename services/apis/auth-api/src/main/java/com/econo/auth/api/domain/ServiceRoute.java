package com.econo.auth.api.domain;

/**
 * ServiceRoute 도메인 객체
 *
 * @param routeId 라우트 UUID
 * @param clientId 연결된 클라이언트 ID
 * @param upstreamUrl 업스트림 서비스 URL
 * @param pathPrefix 경로 접두사
 */
public record ServiceRoute(
		String routeId, String clientId, String upstreamUrl, String pathPrefix) {}
