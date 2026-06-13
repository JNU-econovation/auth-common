package com.econo.auth.api.config;

import com.econo.auth.client.application.service.ProtectedPathPolicy;
import java.util.List;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * 보호 경로 판정 포트 구현체 — 보호 경로 목록의 단일 진실 소스
 *
 * <p>여기 나열한 경로는 동적 라우트로 등록·수정·삭제할 수 없다(가로채기 방지). 값이 배포 환경에 종속되므로 소비자 앱인 auth-api가 소유하며, {@code
 * ApplicationServiceConfig#protectedPathPolicy()} {@code @Bean}으로 등록된다.
 *
 * <p>api-gateway의 정적 보호 라우트(GatewayRoutingConfig)와 수동으로 동기화하여 관리한다(두 앱이 설정을 공유하지 않으므로).
 */
public class ProtectedPathPolicyImpl implements ProtectedPathPolicy {

	/** 보호 경로 패턴 목록 */
	public static final List<String> PROTECTED_PATHS =
			List.of(
					"/api/v1/auth/**",
					"/oauth2/**",
					"/.well-known/**",
					"/userinfo",
					"/swagger-ui/**",
					"/swagger-ui.html",
					"/v3/api-docs/**",
					"/v3/api-docs",
					"/actuator/**",
					"/api/v1/admin/**",
					"/api/v1/members/**",
					"/api/v1/clients/**",
					// 동적 라우트가 내부 전용 엔드포인트를 가로채지 못하도록 보호
					"/api/v1/internal/**");

	private static final PathPatternParser PATTERN_PARSER = new PathPatternParser();

	@Override
	public boolean isProtected(String pathPrefix) {
		if (pathPrefix == null || pathPrefix.isBlank()) {
			return false;
		}
		PathContainer pathContainer = PathContainer.parsePath(pathPrefix);
		return PROTECTED_PATHS.stream()
				.anyMatch(
						pattern -> {
							try {
								PathPattern parsed = PATTERN_PARSER.parse(pattern);
								return parsed.matches(pathContainer);
							} catch (Exception e) {
								return false;
							}
						});
	}
}
