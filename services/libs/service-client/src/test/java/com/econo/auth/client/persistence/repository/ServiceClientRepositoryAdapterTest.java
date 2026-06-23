package com.econo.auth.client.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.econo.auth.client.application.domain.GrantType;
import com.econo.auth.client.application.domain.ServiceClient;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * ServiceClientRepositoryAdapter JPA 슬라이스 테스트
 *
 * <p>findByOwnerId, findByClientIdAndOwnerId, deleteByClientId, updateClientName 신규 포트 메서드를 검증한다.
 * Testcontainers PostgreSQL + Flyway 실제 마이그레이션 적용.
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ServiceClientRepositoryAdapter.class)
@TestPropertySource(
		properties = {
			"spring.flyway.enabled=true",
			"spring.flyway.locations=classpath:db/migration",
			"spring.jpa.hibernate.ddl-auto=none"
		})
class ServiceClientRepositoryAdapterTest {

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

	@Autowired private ServiceClientRepositoryAdapter adapter;

	// ──────────────────────────────────────────────────────────
	// findByOwnerId
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("findByOwnerId — 소유자별 클라이언트 목록 조회")
	class FindByOwnerIdTest {

		@Test
		@DisplayName("ownerId로 저장된 클라이언트 2개 조회 성공")
		void findByOwnerId_withTwoClients_returnsBoth() {
			// given
			Long ownerId = 101L;
			ServiceClient client1 =
					ServiceClient.create(
							"rc-owner-1", "소유자101 앱1", GrantType.AUTHORIZATION_CODE, null, ownerId, "hash1");
			ServiceClient client2 =
					ServiceClient.create(
							"rc-owner-2", "소유자101 앱2", GrantType.AUTHORIZATION_CODE, null, ownerId, "hash2");
			adapter.save(client1);
			adapter.save(client2);

			// when
			List<ServiceClient> results = adapter.findByOwnerId(ownerId);

			// then
			assertThat(results).hasSize(2);
			assertThat(results)
					.extracting(ServiceClient::getRegisteredClientId)
					.containsExactlyInAnyOrder("rc-owner-1", "rc-owner-2");
		}

		@Test
		@DisplayName("해당 ownerId 소유 클라이언트 없으면 빈 목록 반환")
		void findByOwnerId_withNoClients_returnsEmptyList() {
			// given
			Long ownerId = 999L;

			// when
			List<ServiceClient> results = adapter.findByOwnerId(ownerId);

			// then
			assertThat(results).isEmpty();
		}

		@Test
		@DisplayName("타인 ownerId 클라이언트는 조회되지 않음")
		void findByOwnerId_doesNotReturnOtherOwnersClients() {
			// given
			Long ownerId = 201L;
			Long otherOwnerId = 202L;

			ServiceClient myClient =
					ServiceClient.create(
							"rc-mine", "내 앱", GrantType.AUTHORIZATION_CODE, null, ownerId, "myhash");
			ServiceClient otherClient =
					ServiceClient.create(
							"rc-other", "남의 앱", GrantType.AUTHORIZATION_CODE, null, otherOwnerId, "otherhash");
			adapter.save(myClient);
			adapter.save(otherClient);

			// when
			List<ServiceClient> results = adapter.findByOwnerId(ownerId);

			// then
			assertThat(results).hasSize(1);
			assertThat(results.get(0).getRegisteredClientId()).isEqualTo("rc-mine");
		}
	}

	// ──────────────────────────────────────────────────────────
	// findByClientIdAndOwnerId
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("findByClientIdAndOwnerId — 소유권 검증 겸 단건 조회")
	class FindByClientIdAndOwnerIdTest {

		@Test
		@DisplayName("정확한 clientId + ownerId 조합으로 조회 성공")
		void findByClientIdAndOwnerId_withCorrectPair_returnsClient() {
			// given
			Long ownerId = 301L;
			String clientId = "rc-correct-pair";
			ServiceClient client =
					ServiceClient.create(
							clientId, "페어조회앱", GrantType.AUTHORIZATION_CODE, null, ownerId, "parehash");
			adapter.save(client);

			// when
			Optional<ServiceClient> result = adapter.findByClientIdAndOwnerId(clientId, ownerId);

			// then
			assertThat(result).isPresent();
			assertThat(result.get().getRegisteredClientId()).isEqualTo(clientId);
		}

		@Test
		@DisplayName("같은 clientId이지만 다른 ownerId로 조회 시 Optional.empty() 반환 (존재 은닉)")
		void findByClientIdAndOwnerId_withWrongOwnerId_returnsEmpty() {
			// given
			Long actualOwnerId = 401L;
			Long attackerOwnerId = 402L;
			String clientId = "rc-ownership-check";

			ServiceClient client =
					ServiceClient.create(
							clientId, "소유권확인앱", GrantType.AUTHORIZATION_CODE, null, actualOwnerId, "ownhash");
			adapter.save(client);

			// when
			Optional<ServiceClient> result = adapter.findByClientIdAndOwnerId(clientId, attackerOwnerId);

			// then
			assertThat(result).isEmpty();
		}

		@Test
		@DisplayName("존재하지 않는 clientId 조회 시 Optional.empty() 반환")
		void findByClientIdAndOwnerId_withNonExistentClientId_returnsEmpty() {
			// given
			Long ownerId = 501L;
			String nonExistentClientId = "rc-does-not-exist";

			// when
			Optional<ServiceClient> result =
					adapter.findByClientIdAndOwnerId(nonExistentClientId, ownerId);

			// then
			assertThat(result).isEmpty();
		}
	}

	// ──────────────────────────────────────────────────────────
	// deleteByClientId
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("deleteByClientId — service_client 하드 삭제")
	class DeleteByClientIdTest {

		@Test
		@DisplayName("저장된 클라이언트 삭제 후 findByClientIdAndOwnerId로 조회 시 Optional.empty()")
		void deleteByClientId_afterDelete_cannotBeFound() {
			// given
			Long ownerId = 601L;
			String clientId = "rc-to-delete";

			ServiceClient client =
					ServiceClient.create(
							clientId, "삭제할앱", GrantType.AUTHORIZATION_CODE, null, ownerId, "delhash");
			adapter.save(client);

			// 저장 확인
			assertThat(adapter.findByClientIdAndOwnerId(clientId, ownerId)).isPresent();

			// when
			adapter.deleteByClientId(clientId);

			// then
			assertThat(adapter.findByClientIdAndOwnerId(clientId, ownerId)).isEmpty();
		}

		@Test
		@DisplayName("존재하지 않는 clientId 삭제 시 예외 없이 완료")
		void deleteByClientId_withNonExistentId_completesWithoutException() {
			// given
			String nonExistentClientId = "rc-ghost";

			// when / then — 예외 없이 완료
			adapter.deleteByClientId(nonExistentClientId);
		}
	}

	// ──────────────────────────────────────────────────────────
	// updateClientName
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("updateClientName — clientName JPQL UPDATE 검증")
	class UpdateClientNameTest {

		@Test
		@DisplayName("updateClientName 호출 후 clientName이 변경됨")
		void updateClientName_afterUpdate_newNameIsRetrievable() {
			// given
			Long ownerId = 701L;
			String clientId = "rc-rename";

			ServiceClient client =
					ServiceClient.create(
							clientId, "구 이름", GrantType.AUTHORIZATION_CODE, null, ownerId, "renamehash");
			adapter.save(client);

			// when
			adapter.updateClientName(clientId, "새 이름");

			// then
			Optional<ServiceClient> updated = adapter.findByClientIdAndOwnerId(clientId, ownerId);
			assertThat(updated).isPresent();
			assertThat(updated.get().getClientName()).isEqualTo("새 이름");
		}
	}
}
