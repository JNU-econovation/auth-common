package com.econo.auth.client.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.econo.auth.client.application.domain.ServiceRoute;
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
 * ServiceRouteRepositoryAdapter JPA 슬라이스 테스트
 *
 * <p>V9(FK 제거 + registered_client_id nullable), V10(enabled 인덱스), V11(owner_id nullable), V12(인덱스)
 * 마이그레이션을 실제 Flyway로 적용한 스키마 기준. Testcontainers PostgreSQL 사용.
 *
 * <p>DDL-auto 대신 실제 마이그레이션(classpath:db/migration)을 사용하여 V9 nullable·V10 인덱스가 올바르게 적용되었는지 검증한다.
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ServiceRouteRepositoryAdapter.class)
@TestPropertySource(
		properties = {
			"spring.flyway.enabled=true",
			"spring.flyway.locations=classpath:db/migration",
			"spring.jpa.hibernate.ddl-auto=none"
		})
class ServiceRouteRepositoryAdapterTest {

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

	@Autowired private ServiceRouteRepositoryAdapter adapter;

	// ──────────────────────────────────────────────────────────
	// 저장 및 조회
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("저장 및 기본 조회 테스트")
	class SaveAndFindTest {

		@Test
		@DisplayName("save() 후 findById()로 조회 성공")
		void save_andFindById_success() {
			// given
			ServiceRoute route = ServiceRoute.create("/api/v2/alpha", "http://alpha:8080", true);

			// when
			ServiceRoute saved = adapter.save(route);
			Optional<ServiceRoute> found = adapter.findById(saved.routeId());

			// then
			assertThat(found).isPresent();
			assertThat(found.get().pathPrefix()).isEqualTo("/api/v2/alpha");
			assertThat(found.get().upstreamUrl()).isEqualTo("http://alpha:8080");
			assertThat(found.get().enabled()).isTrue();
		}

		@Test
		@DisplayName("존재하지 않는 routeId 조회 시 Optional.empty() 반환")
		void findById_withNonExistentId_returnsEmpty() {
			// when
			Optional<ServiceRoute> found = adapter.findById("non-existent-uuid");

			// then
			assertThat(found).isEmpty();
		}

		@Test
		@DisplayName("findAll() — 저장된 모든 라우트 반환")
		void findAll_returnsAllSavedRoutes() {
			// given
			ServiceRoute route1 = ServiceRoute.create("/api/v2/beta", "http://beta:8080", true);
			ServiceRoute route2 = ServiceRoute.create("/api/v2/gamma", "http://gamma:8080", false);
			adapter.save(route1);
			adapter.save(route2);

			// when
			List<ServiceRoute> all = adapter.findAll();

			// then
			assertThat(all).hasSizeGreaterThanOrEqualTo(2);
			assertThat(all)
					.extracting(ServiceRoute::pathPrefix)
					.contains("/api/v2/beta", "/api/v2/gamma");
		}
	}

	// ──────────────────────────────────────────────────────────
	// enabled 필터
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("enabled 필터 조회 테스트")
	class FindAllEnabledTest {

		@Test
		@DisplayName("findAllEnabled() — enabled=true 라우트만 반환")
		void findAllEnabled_returnsOnlyEnabledRoutes() {
			// given
			ServiceRoute enabledRoute =
					ServiceRoute.create("/api/v2/enabled", "http://enabled:8080", true);
			ServiceRoute disabledRoute =
					ServiceRoute.create("/api/v2/disabled", "http://disabled:8080", false);
			adapter.save(enabledRoute);
			adapter.save(disabledRoute);

			// when
			List<ServiceRoute> enabled = adapter.findAllEnabled();

			// then
			assertThat(enabled).allMatch(ServiceRoute::enabled);
			assertThat(enabled).extracting(ServiceRoute::pathPrefix).contains("/api/v2/enabled");
			assertThat(enabled).extracting(ServiceRoute::pathPrefix).doesNotContain("/api/v2/disabled");
		}
	}

	// ──────────────────────────────────────────────────────────
	// existsByPathPrefix
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("pathPrefix 존재 여부 확인 테스트")
	class ExistsByPathPrefixTest {

		@Test
		@DisplayName("저장된 pathPrefix는 existsByPathPrefix가 true 반환")
		void existsByPathPrefix_returnsTrueForSavedRoute() {
			// given
			ServiceRoute route = ServiceRoute.create("/api/v2/exists", "http://exists:8080", true);
			adapter.save(route);

			// when
			boolean exists = adapter.existsByPathPrefix("/api/v2/exists");

			// then
			assertThat(exists).isTrue();
		}

		@Test
		@DisplayName("미저장 pathPrefix는 existsByPathPrefix가 false 반환")
		void existsByPathPrefix_returnsFalseForUnknownPrefix() {
			// when
			boolean exists = adapter.existsByPathPrefix("/api/v2/not-saved");

			// then
			assertThat(exists).isFalse();
		}

		@Test
		@DisplayName("existsByPathPrefixAndRouteIdNot — 자신 제외 중복 검증")
		void existsByPathPrefixAndRouteIdNot_excludesSelf() {
			// given
			ServiceRoute route = ServiceRoute.create("/api/v2/self-check", "http://self:8080", true);
			ServiceRoute saved = adapter.save(route);

			// when — 자신의 routeId를 제외하면 false
			boolean existsExcludingSelf =
					adapter.existsByPathPrefixAndRouteIdNot("/api/v2/self-check", saved.routeId());

			// then
			assertThat(existsExcludingSelf).isFalse();
		}

		@Test
		@DisplayName("existsByPathPrefixAndRouteIdNot — 다른 라우트와 충돌하면 true")
		void existsByPathPrefixAndRouteIdNot_returnsTrueForConflict() {
			// given
			ServiceRoute routeA = ServiceRoute.create("/api/v2/conflict-target", "http://a:8080", true);
			ServiceRoute routeB = ServiceRoute.create("/api/v2/different", "http://b:8080", true);
			adapter.save(routeA);
			ServiceRoute savedB = adapter.save(routeB);

			// when — routeB가 routeA의 pathPrefix와 충돌
			boolean exists =
					adapter.existsByPathPrefixAndRouteIdNot("/api/v2/conflict-target", savedB.routeId());

			// then
			assertThat(exists).isTrue();
		}
	}

	// ──────────────────────────────────────────────────────────
	// deleteById
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("삭제 테스트")
	class DeleteByIdTest {

		@Test
		@DisplayName("deleteById() 후 findById() 조회 시 Optional.empty() 반환")
		void deleteById_makesRouteUnfindable() {
			// given
			ServiceRoute route = ServiceRoute.create("/api/v2/to-delete", "http://delete:8080", true);
			ServiceRoute saved = adapter.save(route);

			// when
			adapter.deleteById(saved.routeId());
			Optional<ServiceRoute> found = adapter.findById(saved.routeId());

			// then
			assertThat(found).isEmpty();
		}
	}

	// ──────────────────────────────────────────────────────────
	// V9 마이그레이션 검증 — registered_client_id nullable
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("V9 마이그레이션 — registered_client_id nullable 검증")
	class V9MigrationTest {

		@Test
		@DisplayName("registered_client_id 없이(null) 라우트 저장 가능")
		void save_withoutRegisteredClientId_succeeds() {
			// given — V9 이후 registered_client_id는 nullable
			ServiceRoute route =
					ServiceRoute.create("/api/v2/independent", "http://independent:8080", true);

			// when / then — 예외 없이 저장 성공
			ServiceRoute saved = adapter.save(route);
			assertThat(saved.routeId()).isNotBlank();

			Optional<ServiceRoute> found = adapter.findById(saved.routeId());
			assertThat(found).isPresent();
		}
	}

	// ──────────────────────────────────────────────────────────
	// registeredClientId 기반 조회 — client-self-management 신규 포트
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("findByRegisteredClientId — 클라이언트별 라우트 단건 조회")
	class FindByRegisteredClientIdTest {

		@Test
		@DisplayName("registeredClientId로 저장된 라우트 단건 조회 성공")
		void findByRegisteredClientId_withSavedRoute_returnsPresent() {
			// given
			Long ownerId = 50L;
			String registeredClientId = "rc-find-by-id";
			ServiceRoute route =
					ServiceRoute.create(
							"/api/selfns", "http://self-service:8080", true, ownerId, registeredClientId);
			adapter.save(route);

			// when
			Optional<ServiceRoute> result = adapter.findByRegisteredClientId(registeredClientId);

			// then
			assertThat(result).isPresent();
			assertThat(result.get().pathPrefix()).isEqualTo("/api/selfns");
		}

		@Test
		@DisplayName("registeredClientId가 없는 라우트는 조회되지 않음 — Optional.empty()")
		void findByRegisteredClientId_withNullRegisteredClientId_returnsEmpty() {
			// given — 어드민 라우트: registeredClientId=null
			ServiceRoute adminRoute =
					ServiceRoute.create("/api/adminroute", "http://admin-service:8080", true);
			adapter.save(adminRoute);

			// when
			Optional<ServiceRoute> result = adapter.findByRegisteredClientId("rc-not-exist");

			// then
			assertThat(result).isEmpty();
		}

		@Test
		@DisplayName("존재하지 않는 registeredClientId 조회 시 Optional.empty()")
		void findByRegisteredClientId_withUnknownId_returnsEmpty() {
			// when
			Optional<ServiceRoute> result = adapter.findByRegisteredClientId("rc-ghost-uuid");

			// then
			assertThat(result).isEmpty();
		}
	}

	@Nested
	@DisplayName("findByRegisteredClientIdIn — N+1 방지 배치 조회")
	class FindByRegisteredClientIdInTest {

		@Test
		@DisplayName("registeredClientId 목록으로 라우트 배치 조회 성공")
		void findByRegisteredClientIdIn_withMultipleIds_returnsMatchingRoutes() {
			// given
			Long ownerId = 60L;
			String clientId1 = "rc-batch-1";
			String clientId2 = "rc-batch-2";
			adapter.save(
					ServiceRoute.create("/api/batchns1", "http://batch1:8080", true, ownerId, clientId1));
			adapter.save(
					ServiceRoute.create("/api/batchns2", "http://batch2:8080", true, ownerId, clientId2));

			// when
			List<ServiceRoute> results =
					adapter.findByRegisteredClientIdIn(List.of(clientId1, clientId2));

			// then
			assertThat(results).hasSize(2);
			assertThat(results)
					.extracting(ServiceRoute::pathPrefix)
					.containsExactlyInAnyOrder("/api/batchns1", "/api/batchns2");
		}

		@Test
		@DisplayName("registeredClientId 목록 중 일부만 존재해도 존재하는 것만 반환")
		void findByRegisteredClientIdIn_withPartialMatch_returnsOnlyMatching() {
			// given
			Long ownerId = 61L;
			String existingClientId = "rc-partial-exist";
			String nonExistingClientId = "rc-partial-nonexist";
			adapter.save(
					ServiceRoute.create(
							"/api/partialns", "http://partial:8080", true, ownerId, existingClientId));

			// when
			List<ServiceRoute> results =
					adapter.findByRegisteredClientIdIn(List.of(existingClientId, nonExistingClientId));

			// then
			assertThat(results).hasSize(1);
			assertThat(results.get(0).pathPrefix()).isEqualTo("/api/partialns");
		}

		@Test
		@DisplayName("빈 목록으로 조회 시 빈 결과 반환")
		void findByRegisteredClientIdIn_withEmptyList_returnsEmptyList() {
			// when
			List<ServiceRoute> results = adapter.findByRegisteredClientIdIn(List.of());

			// then
			assertThat(results).isEmpty();
		}
	}

	@Nested
	@DisplayName("deleteByRegisteredClientId — 클라이언트 삭제 캐스케이드")
	class DeleteByRegisteredClientIdTest {

		@Test
		@DisplayName("registeredClientId로 라우트 삭제 후 조회 시 Optional.empty()")
		void deleteByRegisteredClientId_afterDelete_cannotBeFound() {
			// given
			Long ownerId = 70L;
			String clientId = "rc-to-cascade-delete";
			adapter.save(
					ServiceRoute.create("/api/cascadedel", "http://cascade:8080", true, ownerId, clientId));
			assertThat(adapter.findByRegisteredClientId(clientId)).isPresent();

			// when
			adapter.deleteByRegisteredClientId(clientId);

			// then
			assertThat(adapter.findByRegisteredClientId(clientId)).isEmpty();
		}

		@Test
		@DisplayName("다른 registeredClientId를 가진 라우트는 삭제되지 않음")
		void deleteByRegisteredClientId_doesNotDeleteOtherRoutes() {
			// given
			Long ownerId = 71L;
			String clientIdToDelete = "rc-delete-target";
			String clientIdToKeep = "rc-keep";
			adapter.save(
					ServiceRoute.create(
							"/api/deletens", "http://delete:8080", true, ownerId, clientIdToDelete));
			adapter.save(
					ServiceRoute.create("/api/keepns", "http://keep:8080", true, ownerId, clientIdToKeep));

			// when
			adapter.deleteByRegisteredClientId(clientIdToDelete);

			// then
			assertThat(adapter.findByRegisteredClientId(clientIdToDelete)).isEmpty();
			assertThat(adapter.findByRegisteredClientId(clientIdToKeep)).isPresent();
		}

		@Test
		@DisplayName("존재하지 않는 registeredClientId로 삭제 시 예외 없이 완료")
		void deleteByRegisteredClientId_withNonExistentId_completesWithoutException() {
			// when / then — 예외 없이 완료
			adapter.deleteByRegisteredClientId("rc-ghost-for-delete");
		}
	}

	@Nested
	@DisplayName("ServiceRouteJpaEntity round-trip — registeredClientId 보존 검증")
	class RegisteredClientIdRoundTripTest {

		@Test
		@DisplayName("5-인자 create 팩토리로 생성된 라우트를 저장하면 registeredClientId가 그대로 복원됨")
		void save_andFind_preservesRegisteredClientId() {
			// given
			Long ownerId = 80L;
			String registeredClientId = "rc-roundtrip-test";
			ServiceRoute route =
					ServiceRoute.create(
							"/api/roundtripns", "http://roundtrip:8080", true, ownerId, registeredClientId);

			// when
			ServiceRoute saved = adapter.save(route);
			Optional<ServiceRoute> found = adapter.findByRegisteredClientId(registeredClientId);

			// then
			assertThat(found).isPresent();
			assertThat(found.get().registeredClientId()).isEqualTo(registeredClientId);
			assertThat(found.get().ownerId()).isEqualTo(ownerId);
		}

		@Test
		@DisplayName("어드민 라우트(3-인자 create)는 registeredClientId가 null로 저장됨")
		void save_adminRoute_registeredClientIdIsNull() {
			// given — 어드민 경로: registeredClientId = null
			ServiceRoute adminRoute =
					ServiceRoute.create("/api/adminnullns", "http://adminnull:8080", true);

			// when
			ServiceRoute saved = adapter.save(adminRoute);
			Optional<ServiceRoute> found = adapter.findById(saved.routeId());

			// then
			assertThat(found).isPresent();
			assertThat(found.get().registeredClientId()).isNull();
		}
	}

	// ──────────────────────────────────────────────────────────
	// V11 마이그레이션 검증 — owner_id 셀프 등록 쿼리 메서드
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("V11 마이그레이션 — owner_id 컬럼 + 네임스페이스 선점 조회 검증")
	class OwnerIdQueryTest {

		@Test
		@DisplayName("findNamespaceOwner — 해당 네임스페이스 라우트 없으면 Optional.empty() 반환")
		void findNamespaceOwner_withNoRoute_returnsEmpty() {
			// when
			Optional<Long> owner = adapter.findNamespaceOwner("nonexistent-ns");

			// then
			assertThat(owner).isEmpty();
		}

		@Test
		@DisplayName("findNamespaceOwner — /api/{ns} 형태로 저장된 라우트의 ownerId 반환")
		void findNamespaceOwner_withExactPrefix_returnsOwnerId() {
			// given
			Long ownerId = 60L;
			adapter.save(ServiceRoute.create("/api/ns-query", "http://nsq:8080", true, ownerId));

			// when
			Optional<Long> owner = adapter.findNamespaceOwner("ns-query");

			// then
			assertThat(owner).isPresent();
			assertThat(owner.get()).isEqualTo(ownerId);
		}

		@Test
		@DisplayName("findNamespaceOwner — /api/{ns}/subpath 형태로 저장된 라우트의 ownerId 반환")
		void findNamespaceOwner_withSubpath_returnsOwnerId() {
			// given
			Long ownerId = 61L;
			adapter.save(ServiceRoute.create("/api/ns-subpath/v1", "http://nssp:8080", true, ownerId));

			// when
			Optional<Long> owner = adapter.findNamespaceOwner("ns-subpath");

			// then
			assertThat(owner).isPresent();
			assertThat(owner.get()).isEqualTo(ownerId);
		}

		@Test
		@DisplayName("findNamespaceOwner — 다른 네임스페이스는 조회되지 않음")
		void findNamespaceOwner_doesNotMatchDifferentNamespace() {
			// given
			Long ownerId = 70L;
			adapter.save(ServiceRoute.create("/api/ns-separate", "http://sep:8080", true, ownerId));

			// when — 다른 네임스페이스로 조회
			Optional<Long> owner = adapter.findNamespaceOwner("other-ns");

			// then
			assertThat(owner).isEmpty();
		}
	}
}
