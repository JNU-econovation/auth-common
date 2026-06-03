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
			// when
			Member member =
					Member.create("홍길동", "honggildong", "$2a$12$hashedPassword", 32, MemberStatus.AM);

			// then
			// id는 JPA 저장 전에는 null — 도메인 계층에서는 null 허용
			assertThat(member.getId()).isNull();
		}
	}
}
