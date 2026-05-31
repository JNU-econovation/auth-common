package com.econo.auth.infra.member.adapter.out.persistence;

import static org.assertj.core.api.Assertions.*;

import com.econo.auth.member.domain.Member;
import com.econo.auth.member.domain.MemberStatus;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(MemberRepositoryAdapter.class)
class MemberRepositoryAdapterTest {

	@Container
	static PostgreSQLContainer<?> postgres =
			new PostgreSQLContainer<>("postgres:16")
					.withDatabaseName("auth_test")
					.withUsername("test")
					.withPassword("test");

	@DynamicPropertySource
	static void configureProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", postgres::getJdbcUrl);
		registry.add("spring.datasource.username", postgres::getUsername);
		registry.add("spring.datasource.password", postgres::getPassword);
	}

	@Autowired private MemberRepositoryAdapter memberRepositoryAdapter;

	@Nested
	@DisplayName("저장 및 조회 테스트")
	class SaveAndFindTest {

		@Test
		@DisplayName("저장 후 loginId로 조회 성공")
		void saveAndFindByLoginId() {
			// given
			Member member =
					Member.create("홍길동", "honggildong", "$2a$12$hashedPassword", 32, MemberStatus.AM);

			// when
			memberRepositoryAdapter.save(member);
			Optional<Member> found = memberRepositoryAdapter.findByLoginId("honggildong");

			// then
			assertThat(found).isPresent();
			assertThat(found.get().getLoginId()).isEqualTo("honggildong");
			assertThat(found.get().getName()).isEqualTo("홍길동");
		}

		@Test
		@DisplayName("존재하지 않는 loginId 조회 시 Optional.empty() 반환")
		void findByNonExistingLoginId() {
			// when
			Optional<Member> found = memberRepositoryAdapter.findByLoginId("nonexistent");

			// then
			assertThat(found).isEmpty();
		}
	}

	@Nested
	@DisplayName("loginId 존재 여부 테스트")
	class ExistsByLoginIdTest {

		@Test
		@DisplayName("저장된 loginId는 existsByLoginId가 true")
		void existsByLoginIdTrue() {
			// given
			Member member =
					Member.create("홍길동", "exist_user", "$2a$12$hashedPassword", 32, MemberStatus.AM);
			memberRepositoryAdapter.save(member);

			// when
			boolean exists = memberRepositoryAdapter.existsByLoginId("exist_user");

			// then
			assertThat(exists).isTrue();
		}

		@Test
		@DisplayName("미저장 loginId는 existsByLoginId가 false")
		void existsByLoginIdFalse() {
			// when
			boolean exists = memberRepositoryAdapter.existsByLoginId("notexist");

			// then
			assertThat(exists).isFalse();
		}
	}

	@Nested
	@DisplayName("DB 제약 위반 테스트")
	class ConstraintViolationTest {

		@Test
		@DisplayName("중복 loginId 저장 시 DataIntegrityViolationException 발생")
		void saveDuplicateLoginIdThrowsException() {
			// given
			Member member1 =
					Member.create("홍길동", "duplicate", "$2a$12$hashedPassword1", 32, MemberStatus.AM);
			Member member2 =
					Member.create("김철수", "duplicate", "$2a$12$hashedPassword2", 33, MemberStatus.RM);
			memberRepositoryAdapter.save(member1);

			// when & then
			assertThatThrownBy(() -> memberRepositoryAdapter.save(member2))
					.isInstanceOf(DataIntegrityViolationException.class);
		}

		@Test
		@DisplayName("generation이 0이면 CHECK 제약 위반")
		void saveWithGenerationZeroThrowsException() {
			// given — generation=0은 CHECK (generation BETWEEN 1 AND 99) 위반
			Member member =
					Member.create("홍길동", "gen_zero_user", "$2a$12$hashedPassword", 0, MemberStatus.AM);

			// when & then
			assertThatThrownBy(() -> memberRepositoryAdapter.save(member))
					.isInstanceOf(Exception.class)
					.satisfies(
							ex ->
									assertThat(ex)
											.isInstanceOfAny(
													DataIntegrityViolationException.class,
													org.springframework.dao.DataAccessException.class));
		}

		@Test
		@DisplayName("generation이 100이면 CHECK 제약 위반")
		void saveWithGenerationHundredThrowsException() {
			// given — generation=100은 CHECK (generation BETWEEN 1 AND 99) 위반
			Member member =
					Member.create("홍길동", "gen_hundred_user", "$2a$12$hashedPassword", 100, MemberStatus.AM);

			// when & then
			assertThatThrownBy(() -> memberRepositoryAdapter.save(member))
					.isInstanceOf(Exception.class)
					.satisfies(
							ex ->
									assertThat(ex)
											.isInstanceOfAny(
													DataIntegrityViolationException.class,
													org.springframework.dao.DataAccessException.class));
		}
	}
}
