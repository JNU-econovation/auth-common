package com.econo.auth.gateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 게이트웨이 JWT 서명·만료 검증 컴포넌트 */
@Component
public class JwtVerifier {

	private final String jwtSecret;

	public JwtVerifier(@Value("${JWT_SECRET}") String jwtSecret) {
		this.jwtSecret = jwtSecret;
	}

	/**
	 * JWT 검증 후 클레임 반환
	 *
	 * @param jwt JWT 문자열
	 * @return 검증된 Claims
	 * @throws io.jsonwebtoken.JwtException 서명 오류 또는 만료 시
	 */
	public Claims verify(String jwt) {
		SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
		return Jwts.parser().verifyWith(key).build().parseSignedClaims(jwt).getPayload();
	}
}
