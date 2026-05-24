package com.econo.auth.infra.member.adapter.out.token;

import com.econo.auth.core.member.application.port.out.TokenIssuer;
import com.econo.auth.core.member.domain.Member;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import javax.crypto.SecretKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** TokenIssuer 포트 jjwt 구현체 (HMAC-SHA256) */
@Slf4j
@Component
public class JwtTokenIssuerAdapter implements TokenIssuer {

	private final String jwtSecret;
	private final long expirySeconds;

	public JwtTokenIssuerAdapter(
			@Value("${JWT_SECRET}") String jwtSecret,
			@Value("${auth.jwt.expiry-seconds}") long expirySeconds) {
		this.jwtSecret = jwtSecret;
		this.expirySeconds = expirySeconds;
	}

	@Override
	public String issue(Member member) {
		SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
		Date now = new Date();
		Date expiration = new Date(now.getTime() + expirySeconds * 1000L);

		return Jwts.builder()
				.subject(member.getId() != null ? String.valueOf(member.getId()) : "0")
				.claim("loginId", member.getLoginId())
				.claim("name", member.getName())
				.claim("generation", member.getGeneration())
				.claim("status", member.getStatus().name())
				.claim("roles", List.of("USER"))
				.issuedAt(now)
				.expiration(expiration)
				.signWith(key)
				.compact();
	}
}
