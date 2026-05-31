package com.econo.auth.gateway.security;

import com.econo.auth.passport.Passport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** JWT 클레임(Spring Security {@link Jwt}) → Passport 생성 및 Base64 직렬화 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PassportBuilder {

	private final ObjectMapper objectMapper;

	/**
	 * Spring Security JWT 클레임에서 Passport를 생성하고 Base64 인코딩된 JSON을 반환
	 *
	 * <p>클레임 매핑: sub → memberId(Long), loginId, name, generation(Integer), status, roles(List)
	 *
	 * @param jwt Spring Security {@link Jwt} 객체
	 * @return Base64 인코딩된 Passport JSON
	 */
	public String buildAndSerialize(Jwt jwt) {
		try {
			Passport passport = buildPassport(jwt);
			String json = objectMapper.writeValueAsString(passport);
			return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
		} catch (Exception e) {
			log.error("Failed to serialize passport: {}", e.getMessage());
			throw new RuntimeException("Failed to serialize passport", e);
		}
	}

	/** Spring Security Jwt 클레임 → Passport 객체 생성 */
	private Passport buildPassport(Jwt jwt) {
		Long memberId = Long.valueOf(jwt.getSubject());
		String loginId = jwt.getClaimAsString("loginId");
		String name = jwt.getClaimAsString("name");

		Object generationRaw = jwt.getClaim("generation");
		Integer generation = null;
		if (generationRaw instanceof Integer) {
			generation = (Integer) generationRaw;
		} else if (generationRaw instanceof Number) {
			generation = ((Number) generationRaw).intValue();
		}

		String status = jwt.getClaimAsString("status");
		List<String> roles = jwt.getClaimAsStringList("roles");

		LocalDateTime issuedAt = toLocalDateTime(jwt.getIssuedAt());
		LocalDateTime expiresAt = toLocalDateTime(jwt.getExpiresAt());

		return new Passport(memberId, loginId, name, generation, status, roles, issuedAt, expiresAt);
	}

	/** {@link Instant} → {@link LocalDateTime} 변환 */
	private LocalDateTime toLocalDateTime(Instant instant) {
		if (instant == null) {
			return null;
		}
		return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
	}
}
