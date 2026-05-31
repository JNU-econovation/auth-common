package com.econo.auth.infra.member.adapter.out.security;

import com.econo.auth.member.application.port.out.PasswordHasher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/** PasswordHasher 포트 BCrypt 구현체 (cost=12) */
@Component
public class BCryptPasswordHasherAdapter implements PasswordHasher {

	private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);

	@Override
	public String hash(String rawPassword) {
		return encoder.encode(rawPassword);
	}

	@Override
	public boolean matches(String rawPassword, String hashedPassword) {
		return encoder.matches(rawPassword, hashedPassword);
	}
}
