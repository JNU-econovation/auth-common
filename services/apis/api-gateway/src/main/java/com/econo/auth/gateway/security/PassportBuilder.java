package com.econo.auth.gateway.security;

import com.econo.common.auth.core.passport.Passport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
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
	 * Spring Security JWT 클레임에서 Passport를 생성하고 Base64 인코딩된 JSON을 반환.
	 *
	 * @param jwt Spring Security {@link Jwt} 객체
	 * @return Base64 인코딩된 Passport JSON
	 * @throws RuntimeException 필수 클레임 누락 또는 직렬화 실패 시
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

	/**
	 * Spring Security Jwt 클레임 → Passport 객체 생성.
	 *
	 * <p>필수 클레임(memberId, loginId, name) 누락 시 즉시 실패하여 불완전한 Passport가 내부 서비스에 전달되는 것을 방지한다.
	 */
	private Passport buildPassport(Jwt jwt) {
		Objects.requireNonNull(jwt.getSubject(), "JWT subject(memberId) 클레임 누락");
		Long memberId = Long.valueOf(jwt.getSubject());

		String loginId = Objects.requireNonNull(jwt.getClaimAsString("loginId"), "loginId 클레임 누락");
		String name = Objects.requireNonNull(jwt.getClaimAsString("name"), "name 클레임 누락");

		Object generationRaw = jwt.getClaim("generation");
		Integer generation = null;
		if (generationRaw instanceof Integer i) {
			generation = i;
		} else if (generationRaw instanceof Number n) {
			generation = n.intValue();
		}

		String status = jwt.getClaimAsString("status");

		List<String> roles = jwt.getClaimAsStringList("roles");
		if (roles == null || roles.isEmpty()) {
			log.warn("roles 클레임 누락 또는 비어있음, memberId={}", memberId);
			roles = List.of("USER");
		}

		LocalDateTime issuedAt = toLocalDateTime(jwt.getIssuedAt());
		LocalDateTime expiresAt = toLocalDateTime(jwt.getExpiresAt());

		return new Passport(memberId, loginId, name, generation, status, roles, issuedAt, expiresAt);
	}

	private LocalDateTime toLocalDateTime(Instant instant) {
		if (instant == null) {
			return null;
		}
		return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
	}
}
