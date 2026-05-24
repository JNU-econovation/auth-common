package com.econo.auth.gateway.security;

import com.econo.common.auth.core.passport.Passport;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** JWT 클레임 → Passport 생성 및 Base64 직렬화 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PassportBuilder {

	private final ObjectMapper objectMapper;

	/**
	 * JWT 클레임에서 Passport를 생성하고 Base64 인코딩된 JSON을 반환
	 *
	 * @param claims JWT 클레임
	 * @return Base64 인코딩된 Passport JSON
	 */
	public String buildAndSerialize(Claims claims) {
		try {
			Passport passport = buildPassport(claims);
			String json = objectMapper.writeValueAsString(passport);
			return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
		} catch (Exception e) {
			log.error("Failed to serialize passport: {}", e.getMessage());
			throw new RuntimeException("Failed to serialize passport", e);
		}
	}

	/** JWT 클레임 → Passport 객체 생성 */
	private Passport buildPassport(Claims claims) {
		Long memberId = Long.valueOf(claims.getSubject());
		String loginId = claims.get("loginId", String.class);
		String name = claims.get("name", String.class);
		Integer generation = claims.get("generation", Integer.class);
		String status = claims.get("status", String.class);

		@SuppressWarnings("unchecked")
		List<String> roles = (List<String>) claims.get("roles", List.class);

		LocalDateTime issuedAt = toLocalDateTime(claims.getIssuedAt());
		LocalDateTime expiresAt = toLocalDateTime(claims.getExpiration());

		return new Passport(memberId, loginId, name, generation, status, roles, issuedAt, expiresAt);
	}

	/** java.util.Date → LocalDateTime 변환 */
	private LocalDateTime toLocalDateTime(Date date) {
		if (date == null) {
			return null;
		}
		Instant instant = date.toInstant();
		return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
	}
}
