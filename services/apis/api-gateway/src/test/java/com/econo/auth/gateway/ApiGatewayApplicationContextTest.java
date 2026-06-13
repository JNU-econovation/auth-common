package com.econo.auth.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 게이트웨이 ApplicationContext 로드 검증
 *
 * <p><b>회귀 방지</b>: {@code DynamicRouteConfig}가 자신이 {@code @Bean}으로 생성하는 {@code
 * DynamicRouteDefinitionRepository}를 필드 주입하면 자기참조 순환 의존이 되어 컨텍스트 기동이 실패한다(앱이 아예 뜨지 않음). 단위 테스트는 전체
 * 컨텍스트를 띄우지 않아 이를 놓쳤다. 이 테스트가 컨텍스트 전체 기동을 검증하여 해당 회귀를 잡는다.
 *
 * <p>auth-api가 없어도 {@code ApplicationReadyEvent} 초기 로드는 예외를 흡수하므로 컨텍스트는 정상 기동한다.
 */
@SpringBootTest(
		properties = {
			"AUTH_API_URI=http://localhost:8081",
			"AUTH_JWKS_URI=http://localhost:8081/oauth2/jwks",
			"AUTH_ISSUER_URI=http://localhost:8080",
			"GATEWAY_INTERNAL_SECRET=test-secret",
			"CORS_ALLOWED_ORIGINS=http://localhost:3000"
		})
class ApiGatewayApplicationContextTest {

	@Test
	@DisplayName("ApplicationContext가 순환 의존 없이 정상 기동한다")
	void contextLoads() {}
}
