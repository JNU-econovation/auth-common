package com.econo.auth.client.application.service;

import com.econo.auth.client.exception.RouteNamespaceInvalidException;

/**
 * pathPrefix에서 네임스페이스를 추출하고 셀프 등록 포맷(/api/{namespace}/...)을 검증하는 유틸 클래스.
 *
 * <p>파싱 규칙:
 *
 * <ul>
 *   <li>pathPrefix가 null 또는 blank이면 {@link RouteNamespaceInvalidException} 발생
 *   <li>선행 슬래시 정규화 후 {@code /}로 분리
 *   <li>세그먼트 0이 {@code api}가 아니면 예외
 *   <li>세그먼트 1이 없거나 blank이면 예외
 *   <li>세그먼트 1을 반환 (소문자 강제 없음 — 원본 그대로)
 * </ul>
 *
 * <p>예시: {@code /api/eeos/**} → {@code eeos}, {@code /api/my-service/v1/**} → {@code my-service},
 * {@code /eeos/**} → 예외, {@code /api/**} → 예외
 *
 * <p>Spring 빈으로 등록하지 않고 {@link RegisterOAuthClientService}가 {@code new}로 생성한다.
 */
public class RouteNamespaceExtractor {

	/**
	 * pathPrefix에서 네임스페이스(두 번째 세그먼트)를 추출한다.
	 *
	 * @param pathPrefix 검사할 경로 접두사
	 * @return 네임스페이스 문자열 (원본 대소문자 유지)
	 * @throws RouteNamespaceInvalidException pathPrefix가 유효하지 않은 형태일 때
	 */
	public String extract(String pathPrefix) {
		if (pathPrefix == null || pathPrefix.isBlank()) {
			throw new RouteNamespaceInvalidException(pathPrefix);
		}

		// 선행 슬래시 제거 후 세그먼트 파싱
		String normalized = pathPrefix.startsWith("/") ? pathPrefix.substring(1) : pathPrefix;

		// 빈 문자열이면 예외 (예: "/" 입력)
		if (normalized.isBlank()) {
			throw new RouteNamespaceInvalidException(pathPrefix);
		}

		String[] segments = normalized.split("/", -1);

		// 세그먼트 0이 "api"가 아니면 예외
		if (segments.length < 1 || !"api".equals(segments[0])) {
			throw new RouteNamespaceInvalidException(pathPrefix);
		}

		// 세그먼트 1이 없거나 blank이거나 와일드카드이면 예외
		if (segments.length < 2 || segments[1].isBlank() || segments[1].equals("**")) {
			throw new RouteNamespaceInvalidException(pathPrefix);
		}

		return segments[1];
	}
}
