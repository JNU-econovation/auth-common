package com.econo.auth.member.persistence.repository;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class BCryptPasswordHasherAdapterTest {

	private BCryptPasswordHasherAdapter passwordHasher;

	@BeforeEach
	void setUp() {
		passwordHasher = new BCryptPasswordHasherAdapter();
	}

	@Nested
	@DisplayName("hash() 테스트")
	class HashTest {

		@Test
		@DisplayName("해시 결과는 원문과 다르다")
		void hashResultDiffersFromRaw() {
			// given
			String rawPassword = "Econo1234!";

			// when
			String hashed = passwordHasher.hash(rawPassword);

			// then
			assertThat(hashed).isNotEqualTo(rawPassword);
		}

		@Test
		@DisplayName("같은 비밀번호라도 매 호출마다 다른 해시가 생성된다 (salt)")
		void samePasswordProducesDifferentHashes() {
			// given
			String rawPassword = "Econo1234!";

			// when
			String hash1 = passwordHasher.hash(rawPassword);
			String hash2 = passwordHasher.hash(rawPassword);

			// then
			assertThat(hash1).isNotEqualTo(hash2);
		}
	}

	@Nested
	@DisplayName("matches() 테스트")
	class MatchesTest {

		@Test
		@DisplayName("올바른 비밀번호는 matches()가 true")
		void correctPasswordMatches() {
			// given
			String rawPassword = "Econo1234!";
			String hashed = passwordHasher.hash(rawPassword);

			// when
			boolean result = passwordHasher.matches(rawPassword, hashed);

			// then
			assertThat(result).isTrue();
		}

		@Test
		@DisplayName("틀린 비밀번호는 matches()가 false")
		void wrongPasswordDoesNotMatch() {
			// given
			String rawPassword = "Econo1234!";
			String hashed = passwordHasher.hash(rawPassword);

			// when
			boolean result = passwordHasher.matches("wrongPassword!", hashed);

			// then
			assertThat(result).isFalse();
		}
	}
}
