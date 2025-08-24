package com.econo.common.auth.core.passport;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PassportTest {

	@Nested
	@DisplayName("Passport 생성 테스트")
	class CreationTest {

		@Test
		@DisplayName("정상적인 Passport 생성")
		void createValidPassport() {
			// given
			Long memberId = 123L;
			String email = "test@eeos.com";
			String name = "테스터";
			List<String> roles = List.of("USER", "MANAGER");
			LocalDateTime issuedAt = LocalDateTime.now();
			LocalDateTime expiresAt = LocalDateTime.now().plusHours(1);

			// when
			Passport passport = new Passport(memberId, email, name, roles, issuedAt, expiresAt);

			// then
			assertThat(passport.getMemberId()).isEqualTo(memberId);
			assertThat(passport.getEmail()).isEqualTo(email);
			assertThat(passport.getName()).isEqualTo(name);
			assertThat(passport.getRoles()).containsExactlyElementsOf(roles);
			assertThat(passport.getIssuedAt()).isEqualTo(issuedAt);
			assertThat(passport.getExpiresAt()).isEqualTo(expiresAt);
		}

		@Test
		@DisplayName("null roles로 생성 시 빈 리스트")
		void createWithNullRoles() {
			// given
			LocalDateTime now = LocalDateTime.now();

			// when
			Passport passport = new Passport(123L, "test@eeos.com", "테스터", null, now, now.plusHours(1));

			// then
			assertThat(passport.getRoles()).isEmpty();
		}

		@Test
		@DisplayName("roles는 불변 리스트")
		void rolesAreImmutable() {
			// given
			List<String> roles = List.of("USER");
			LocalDateTime now = LocalDateTime.now();
			Passport passport = new Passport(123L, "test@eeos.com", "테스터", roles, now, now.plusHours(1));

			// when & then
			assertThatThrownBy(() -> passport.getRoles().add("ADMIN"))
					.isInstanceOf(UnsupportedOperationException.class);
		}
	}

	@Nested
	@DisplayName("권한 확인 테스트")
	class RoleCheckTest {

		private Passport createTestPassport(String... roles) {
			LocalDateTime now = LocalDateTime.now();
			return new Passport(123L, "test@eeos.com", "테스터", List.of(roles), now, now.plusHours(1));
		}

		@Test
		@DisplayName("관리자 권한 확인")
		void isAdmin() {
			// given
			Passport adminPassport = createTestPassport("ADMIN", "USER");
			Passport userPassport = createTestPassport("USER");

			// when & then
			assertThat(adminPassport.isAdmin()).isTrue();
			assertThat(userPassport.isAdmin()).isFalse();
		}

		@Test
		@DisplayName("매니저 권한 확인")
		void isManager() {
			// given
			Passport managerPassport = createTestPassport("MANAGER", "USER");
			Passport userPassport = createTestPassport("USER");

			// when & then
			assertThat(managerPassport.isManager()).isTrue();
			assertThat(userPassport.isManager()).isFalse();
		}

		@Test
		@DisplayName("특정 권한 보유 확인")
		void hasRole() {
			// given
			Passport passport = createTestPassport("USER", "ACTIVE");

			// when & then
			assertThat(passport.hasRole("USER")).isTrue();
			assertThat(passport.hasRole("ACTIVE")).isTrue();
			assertThat(passport.hasRole("ADMIN")).isFalse();
		}

		@Test
		@DisplayName("여러 권한 중 하나라도 보유하는지 확인")
		void hasAnyRole() {
			// given
			Passport passport = createTestPassport("USER", "ACTIVE");

			// when & then
			assertThat(passport.hasAnyRole("ADMIN", "USER")).isTrue();
			assertThat(passport.hasAnyRole("ADMIN", "MANAGER")).isFalse();
			assertThat(passport.hasAnyRole()).isFalse();
		}

		@Test
		@DisplayName("모든 권한을 보유하는지 확인")
		void hasAllRoles() {
			// given
			Passport passport = createTestPassport("USER", "ACTIVE", "VERIFIED");

			// when & then
			assertThat(passport.hasAllRoles("USER", "ACTIVE")).isTrue();
			assertThat(passport.hasAllRoles("USER", "ADMIN")).isFalse();
			assertThat(passport.hasAllRoles()).isTrue(); // 빈 배열은 true
		}
	}

	@Nested
	@DisplayName("유효성 검증 테스트")
	class ValidationTest {

		@Test
		@DisplayName("유효한 Passport")
		void validPassport() {
			// given
			LocalDateTime now = LocalDateTime.now();
			Passport passport =
					new Passport(123L, "test@eeos.com", "테스터", List.of("USER"), now, now.plusHours(1));

			// when & then
			assertThat(passport.isValid()).isTrue();
			assertThat(passport.isExpired()).isFalse();
			assertThat(passport.isActive()).isTrue();
		}

		@Test
		@DisplayName("만료된 Passport")
		void expiredPassport() {
			// given
			LocalDateTime now = LocalDateTime.now();
			Passport passport =
					new Passport(
							123L, "test@eeos.com", "테스터", List.of("USER"), now.minusHours(2), now.minusHours(1));

			// when & then
			assertThat(passport.isExpired()).isTrue();
			assertThat(passport.isValid()).isTrue();
			assertThat(passport.isActive()).isFalse();
		}

		@Test
		@DisplayName("memberId가 null인 경우")
		void nullMemberId() {
			// given
			LocalDateTime now = LocalDateTime.now();
			Passport passport =
					new Passport(null, "test@eeos.com", "테스터", List.of("USER"), now, now.plusHours(1));

			// when & then
			assertThat(passport.isValid()).isFalse();
		}

		@Test
		@DisplayName("expiresAt이 null인 경우")
		void nullExpiresAt() {
			// given
			LocalDateTime now = LocalDateTime.now();
			Passport passport = new Passport(123L, "test@eeos.com", "테스터", List.of("USER"), now, null);

			// when & then
			assertThat(passport.isExpired()).isFalse(); // null이면 만료되지 않음
			assertThat(passport.isValid()).isTrue();
		}
	}

	@Nested
	@DisplayName("접근 권한 확인 테스트")
	class AccessControlTest {

		@Test
		@DisplayName("특정 사용자인지 확인")
		void isMember() {
			// given
			Passport passport =
					new Passport(
							123L,
							"test@eeos.com",
							"테스터",
							List.of("USER"),
							LocalDateTime.now(),
							LocalDateTime.now().plusHours(1));

			// when & then
			assertThat(passport.isMember(123L)).isTrue();
			assertThat(passport.isMember(456L)).isFalse();
			assertThat(passport.isMember(null)).isFalse();
		}

		@Test
		@DisplayName("자신 또는 관리자인지 확인")
		void canAccessMember() {
			// given
			LocalDateTime now = LocalDateTime.now();
			Passport userPassport =
					new Passport(123L, "user@eeos.com", "사용자", List.of("USER"), now, now.plusHours(1));
			Passport adminPassport =
					new Passport(456L, "admin@eeos.com", "관리자", List.of("ADMIN"), now, now.plusHours(1));

			// when & then
			// 자신의 정보 접근
			assertThat(userPassport.canAccessMember(123L)).isTrue();
			assertThat(userPassport.canAccessMember(456L)).isFalse();

			// 관리자는 모든 정보 접근 가능
			assertThat(adminPassport.canAccessMember(123L)).isTrue();
			assertThat(adminPassport.canAccessMember(456L)).isTrue();
			assertThat(adminPassport.canAccessMember(789L)).isTrue();
		}
	}

	@Nested
	@DisplayName("equals/hashCode 테스트")
	class EqualsHashCodeTest {

		@Test
		@DisplayName("같은 memberId를 가진 Passport는 동일")
		void sameMembrId() {
			// given
			LocalDateTime now = LocalDateTime.now();
			Passport passport1 =
					new Passport(123L, "test1@eeos.com", "테스터1", List.of("USER"), now, now.plusHours(1));
			Passport passport2 =
					new Passport(123L, "test2@eeos.com", "테스터2", List.of("ADMIN"), now, now.plusHours(2));

			// when & then
			assertThat(passport1).isEqualTo(passport2);
			assertThat(passport1.hashCode()).isEqualTo(passport2.hashCode());
		}

		@Test
		@DisplayName("다른 memberId를 가진 Passport는 다름")
		void differentMemberId() {
			// given
			LocalDateTime now = LocalDateTime.now();
			Passport passport1 =
					new Passport(123L, "test@eeos.com", "테스터", List.of("USER"), now, now.plusHours(1));
			Passport passport2 =
					new Passport(456L, "test@eeos.com", "테스터", List.of("USER"), now, now.plusHours(1));

			// when & then
			assertThat(passport1).isNotEqualTo(passport2);
		}
	}

	@Nested
	@DisplayName("toString 테스트")
	class ToStringTest {

		@Test
		@DisplayName("toString에 필수 정보가 포함되어야 함")
		void containsEssentialInfo() {
			// given
			LocalDateTime now = LocalDateTime.now();
			Passport passport =
					new Passport(
							123L, "test@eeos.com", "테스터", List.of("USER", "ACTIVE"), now, now.plusHours(1));

			// when
			String result = passport.toString();

			// then
			assertThat(result)
					.contains("123")
					.contains("테스터")
					.contains("USER")
					.contains("ACTIVE")
					.contains("isExpired=false");
		}
	}
}
