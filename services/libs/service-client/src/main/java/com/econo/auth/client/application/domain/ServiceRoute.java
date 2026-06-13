package com.econo.auth.client.application.domain;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Gateway 라우트 식별자·경로·업스트림·활성 여부를 담는 불변 도메인 record
 *
 * <p>정적 팩토리 {@link #create(String, String, boolean)}를 통해 routeId를 UUID로 자동 생성한다.
 */
public record ServiceRoute(
		String routeId,
		String pathPrefix,
		String upstreamUrl,
		boolean enabled,
		LocalDateTime createdAt,
		LocalDateTime updatedAt) {

	/**
	 * 신규 라우트 생성 팩토리 메서드
	 *
	 * <p>routeId는 {@code UUID.randomUUID().toString()}으로 생성되며, 타임스탬프는 null(JPA Auditing에 위임)이다.
	 *
	 * @param pathPrefix 경로 접두사
	 * @param upstreamUrl 업스트림 서비스 URL
	 * @param enabled 활성화 여부
	 * @return 새 ServiceRoute 인스턴스
	 */
	public static ServiceRoute create(String pathPrefix, String upstreamUrl, boolean enabled) {
		return new ServiceRoute(
				UUID.randomUUID().toString(), pathPrefix, upstreamUrl, enabled, null, null);
	}
}
