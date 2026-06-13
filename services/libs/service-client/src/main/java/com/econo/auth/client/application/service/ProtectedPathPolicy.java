package com.econo.auth.client.application.service;

/**
 * 보호 경로 판정 포트
 *
 * <p>{@link ManageRouteService}가 라우트 등록·수정·삭제 시 대상 pathPrefix가 보호 경로인지 확인하는 데 사용한다. 보호 경로이면 {@link
 * com.econo.auth.client.exception.RouteProtectedException}을 발생시킨다.
 *
 * <p>보호 경로 목록의 실제 값은 배포 환경(어느 경로가 게이트웨이 정적 라우트인지)에 종속되므로, 구현체는 소비자 앱( {@code
 * com.econo.auth.api.config.ProtectedPathPolicyImpl})이 제공한다. service-client는 판정 추상화만 정의하고 값을 소유하지
 * 않는다.
 */
public interface ProtectedPathPolicy {

	/**
	 * pathPrefix가 보호 경로에 해당하는지 확인
	 *
	 * @param pathPrefix 검사할 경로 접두사
	 * @return 보호 경로이면 true
	 */
	boolean isProtected(String pathPrefix);
}
