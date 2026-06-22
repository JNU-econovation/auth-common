package com.econo.auth.client.application.domain;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Gateway 라우트 식별자·경로·업스트림·활성 여부·소유자를 담는 불변 도메인 record
 *
 * <p>정적 팩토리 {@link #create(String, String, boolean)}를 통해 routeId를 UUID로 자동 생성한다. (어드민용,
 * ownerId=null)
 *
 * <p>{@link #create(String, String, boolean, Long)}를 통해 셀프 등록 시 ownerId를 포함한 라우트를 생성한다.
 */
public record ServiceRoute(
		String routeId,
		String pathPrefix,
		String upstreamUrl,
		boolean enabled,
		LocalDateTime createdAt,
		LocalDateTime updatedAt,
		Long ownerId) {

	/**
	 * ownerId 없이 6-인자로 생성하는 편의 생성자 (기존 호환성 유지 — ownerId=null)
	 *
	 * @param routeId 라우트 UUID 문자열
	 * @param pathPrefix 경로 접두사
	 * @param upstreamUrl 업스트림 서비스 URL
	 * @param enabled 활성화 여부
	 * @param createdAt 생성 시각
	 * @param updatedAt 수정 시각
	 */
	public ServiceRoute(
			String routeId,
			String pathPrefix,
			String upstreamUrl,
			boolean enabled,
			LocalDateTime createdAt,
			LocalDateTime updatedAt) {
		this(routeId, pathPrefix, upstreamUrl, enabled, createdAt, updatedAt, null);
	}

	/**
	 * 신규 라우트 생성 팩토리 메서드 (어드민용 — ownerId=null)
	 *
	 * <p>routeId는 {@code UUID.randomUUID().toString()}으로 생성되며, 타임스탬프는 null(JPA Auditing에 위임)이다.
	 *
	 * @param pathPrefix 경로 접두사
	 * @param upstreamUrl 업스트림 서비스 URL
	 * @param enabled 활성화 여부
	 * @return 새 ServiceRoute 인스턴스 (ownerId=null)
	 */
	public static ServiceRoute create(String pathPrefix, String upstreamUrl, boolean enabled) {
		return new ServiceRoute(
				UUID.randomUUID().toString(), pathPrefix, upstreamUrl, enabled, null, null, null);
	}

	/**
	 * 신규 라우트 생성 팩토리 메서드 (셀프 등록용 — ownerId 포함)
	 *
	 * <p>routeId는 {@code UUID.randomUUID().toString()}으로 생성되며, 타임스탬프는 null(JPA Auditing에 위임)이다.
	 *
	 * @param pathPrefix 경로 접두사
	 * @param upstreamUrl 업스트림 서비스 URL
	 * @param enabled 활성화 여부
	 * @param ownerId 소유자 회원 ID (null 불가 — 셀프 등록 시 반드시 양수)
	 * @return 새 ServiceRoute 인스턴스 (ownerId 포함)
	 * @throws IllegalArgumentException ownerId가 null이거나 양수가 아닐 때
	 */
	public static ServiceRoute create(
			String pathPrefix, String upstreamUrl, boolean enabled, Long ownerId) {
		if (ownerId == null || ownerId <= 0) {
			throw new IllegalArgumentException("ownerId는 양수여야 합니다. ownerId=" + ownerId);
		}
		return new ServiceRoute(
				UUID.randomUUID().toString(), pathPrefix, upstreamUrl, enabled, null, null, ownerId);
	}
}
