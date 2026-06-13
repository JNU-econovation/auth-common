package com.econo.auth.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** ProtectedPathPolicyImpl 단위 테스트 */
class ProtectedPathPolicyImplTest {

	private final ProtectedPathPolicyImpl policy = new ProtectedPathPolicyImpl();

	@Nested
	@DisplayName("보호 경로는 isProtected=true")
	class ProtectedTest {

		@ParameterizedTest
		@DisplayName("auth 핵심·관리·내부 경로는 보호 대상")
		@ValueSource(
				strings = {
					"/api/v1/auth/login",
					"/oauth2/token",
					"/.well-known/openid-configuration",
					"/userinfo",
					"/api/v1/admin/routes",
					"/api/v1/members/123",
					"/api/v1/clients/abc",
					"/api/v1/internal/routes",
					"/actuator/health"
				})
		void protectedPaths_returnTrue(String path) {
			assertThat(policy.isProtected(path)).isTrue();
		}
	}

	@Nested
	@DisplayName("일반 서비스 경로는 isProtected=false")
	class UnprotectedTest {

		@ParameterizedTest
		@DisplayName("동적 등록 가능한 경로는 보호 대상 아님")
		@ValueSource(strings = {"/api/v2/new-service", "/eeos/members", "/api/v1/foo"})
		void unprotectedPaths_returnFalse(String path) {
			assertThat(policy.isProtected(path)).isFalse();
		}

		@Test
		@DisplayName("null·빈 문자열은 false")
		void nullOrBlank_returnFalse() {
			assertThat(policy.isProtected(null)).isFalse();
			assertThat(policy.isProtected("")).isFalse();
			assertThat(policy.isProtected("  ")).isFalse();
		}
	}
}
