package com.econo.auth.api.adapter.in.web;

import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.econo.auth.api.config.SecurityConfig;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** JwksController 웹 레이어 테스트 — 경로 /api/v1/admin/jwks 검증 */
@WebMvcTest(JwksController.class)
@Import(SecurityConfig.class)
class JwksControllerTest {

	@Autowired private MockMvc mockMvc;

	@MockBean private JWKSource<SecurityContext> jwkSource;

	@Test
	@DisplayName("GET /api/v1/admin/jwks → 200 + keys 배열 반환")
	void jwks_returnsKeysArray() throws Exception {
		given(jwkSource.get(any(), any())).willReturn(List.of());

		mockMvc
				.perform(get("/api/v1/admin/jwks"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.keys").isArray());
	}

	@Test
	@DisplayName("인증 없이도 접근 가능 (Gateway가 직접 호출하는 내부 경로)")
	void jwks_accessibleWithoutAuth() throws Exception {
		given(jwkSource.get(any(), any())).willReturn(List.of());

		mockMvc.perform(get("/api/v1/admin/jwks")).andExpect(status().isOk());
	}

	@Test
	@DisplayName("구 경로 /oauth2/jwks → 200 아님 (더 이상 JWKS 응답 안 함)")
	void oldPath_doesNotReturnJwks() throws Exception {
		// SecurityConfig의 anyRequest().authenticated() 로 401 반환
		// 핵심: 200이 아니면 구 경로로 JWKS가 노출되지 않음
		mockMvc.perform(get("/oauth2/jwks")).andExpect(status().isUnauthorized());
	}
}
