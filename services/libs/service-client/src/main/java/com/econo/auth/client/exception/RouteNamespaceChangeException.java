package com.econo.auth.client.exception;

/**
 * PUT 수정 시 pathPrefix의 네임스페이스가 기존과 달라졌을 때 발생 — 400 ROUTE_NAMESPACE_CHANGE_DENIED
 *
 * <p>네임스페이스 변경은 "새 등록 + 기존 삭제"에 해당하는 의미적 변경으로, 현재 엔드포인트가 허용하지 않는 동작이다. 다른 네임스페이스를 사용하려면 클라이언트를 새로
 * 등록해야 한다.
 */
public class RouteNamespaceChangeException extends RuntimeException {

	private RouteNamespaceChangeException(String existingNamespace, String newNamespace) {
		super(
				"네임스페이스는 변경할 수 없습니다. existing="
						+ existingNamespace
						+ ", requested="
						+ newNamespace
						+ ". 다른 네임스페이스를 사용하려면 클라이언트를 새로 등록하세요.");
	}

	/**
	 * 네임스페이스 변경 거부 예외 생성
	 *
	 * @param existing 기존 라우트의 네임스페이스
	 * @param requested 요청된 새 네임스페이스
	 * @return RouteNamespaceChangeException 인스턴스
	 */
	public static RouteNamespaceChangeException denied(String existing, String requested) {
		return new RouteNamespaceChangeException(existing, requested);
	}
}
