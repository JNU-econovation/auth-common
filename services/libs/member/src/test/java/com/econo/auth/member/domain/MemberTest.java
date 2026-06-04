package com.econo.auth.member.domain;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class MemberTest {

	@Nested
	@DisplayName("Member 팩토리 메서드 테스트")
	class FactoryMethodTest {

		@Test
		@DisplayName("Member.create()로 정상 생성")
		void createMember() {
			// given
			String name = "홍길동";
			String loginId = "honggildong";
			String hashedPassword = "$2a$12$hashedPassword";
			Integer generation = 32;
			MemberStatus status = MemberStatus.AM;

			// when
			Member member = Member.create(name, loginId, hashedPassword, generation, status);

			// then
			assertThat(member.getName()).isEqualTo(name);
			assertThat(member.getLoginId()).isEqualTo(loginId);
			assertThat(member.getHashedPassword()).isEqualTo(hashedPassword);
			assertThat(member.getGeneration()).isEqualTo(generation);
			assertThat(member.getStatus()).isEqualTo(status);
			assertThat(member.getCreatedAt()).isNotNull();
		}

		@Test
		@DisplayName("createdAt은 생성 시 자동 설정")
		void createdAtIsSetOnCreation() {
			// when
			Member member =
					Member.create("홍길동", "honggildong", "$2a$12$hashedPassword", 32, MemberStatus.AM);

			// then
			assertThat(member.getCreatedAt()).isNotNull();
		}
	}

	@Nested
	@DisplayName("MemberStatus enum 테스트")
	class MemberStatusTest {

		@Test
		@DisplayName("MemberStatus는 AM, RM, CM, OB 4개 값을 가진다")
		void memberStatusHasFourValues() {
			// when
			MemberStatus[] values = MemberStatus.values();

			// then
			assertThat(values).hasSize(4);
			assertThat(values)
					.containsExactlyInAnyOrder(
							MemberStatus.AM, MemberStatus.RM, MemberStatus.CM, MemberStatus.OB);
		}
	}

	@Nested
	@DisplayName("Member 불변성 테스트")
	class ImmutabilityTest {

		@Test
		@DisplayName("Member는 id가 null일 수 있다 (저장 전)")
		void memberIdIsNullBeforePersistence() {
			Member member =
					Member.create("홍길동", "honggildong", "$2a$12$hashedPassword", 32, MemberStatus.AM);

			assertThat(member.getId()).isNull();
		}
	}

	@Nested
	@DisplayName("role 필드 테스트")
	class RoleTest {

		@Test
		@DisplayName("create()로 생성된 회원은 기본 role이 USER")
		void create_defaultRoleIsUser() {
			Member member =
					Member.create("홍길동", "honggildong", "$2a$12$hashedPassword", 32, MemberStatus.AM);

			assertThat(member.getRole()).isEqualTo("USER");
			assertThat(member.isAdmin()).isFalse();
		}

		@Test
		@DisplayName("restore()로 ADMIN role 복원 시 isAdmin() true")
		void restore_adminRoleIsAdmin() {
			Member member =
					Member.restore(1L, "홍길동", "honggildong", "hash", 32, MemberStatus.AM, "ADMIN", null);

			assertThat(member.getRole()).isEqualTo("ADMIN");
			assertThat(member.isAdmin()).isTrue();
		}

		@Test
		@DisplayName("restore()로 SUPER_ADMIN role 복원 시 isAdmin() true")
		void restore_superAdminRoleIsAdmin() {
			Member member =
					Member.restore(
							1L, "홍길동", "honggildong", "hash", 32, MemberStatus.AM, "SUPER_ADMIN", null);

			assertThat(member.getRole()).isEqualTo("SUPER_ADMIN");
			assertThat(member.isAdmin()).isTrue();
		}

		@Test
		@DisplayName("withRole()로 역할 변경 — 원본은 불변")
		void withRole_returnsNewInstanceWithChangedRole() {
			Member original =
					Member.restore(1L, "홍길동", "honggildong", "hash", 32, MemberStatus.AM, "USER", null);

			Member promoted = original.withRole("ADMIN");

			assertThat(original.getRole()).isEqualTo("USER");
			assertThat(promoted.getRole()).isEqualTo("ADMIN");
			assertThat(promoted.getId()).isEqualTo(original.getId());
		}

		@Test
		@DisplayName("null role 입력 시 기본값 USER 적용")
		void nullRole_defaultsToUser() {
			Member member =
					Member.restore(1L, "홍길동", "honggildong", "hash", 32, MemberStatus.AM, null, null);

			assertThat(member.getRole()).isEqualTo("USER");
		}
	}
}
