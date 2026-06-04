package com.econo.auth.api.adapter.in.web;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * JWKS 엔드포인트 — api-gateway의 JWT 서명 검증용 공개키 제공
 *
 * <p>gateway가 {@code AUTH_JWKS_URI}로 내부 직접 호출한다. 외부 노출 금지.
 */
@Hidden
@RestController
@RequiredArgsConstructor
public class JwksController {

	private final JWKSource<SecurityContext> jwkSource;

	@GetMapping("/api/v1/admin/jwks")
	public Map<String, Object> jwks() {
		try {
			JWKSelector selector = new JWKSelector(new JWKMatcher.Builder().build());
			List<JWK> keys = jwkSource.get(selector, null);
			List<Map<String, Object>> keyList =
					keys.stream().map(jwk -> jwk.toPublicJWK().toJSONObject()).toList();
			return Map.of("keys", keyList);
		} catch (Exception e) {
			throw new RuntimeException("JWKS 조회 실패", e);
		}
	}
}
