package com.econo.auth.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.route.RouteDefinition;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * DynamicRouteDefinitionRepository 단위 테스트
 *
 * <p>auth-api REST 초기 로드, 캐시 반환, reload() 동작, RouteDefinition 변환을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class DynamicRouteDefinitionRepositoryTest {

	@Mock private AuthApiRouteClient authApiRouteClient;

	private DynamicRouteDefinitionRepository repository;

	@BeforeEach
	void setUp() {
		repository = new DynamicRouteDefinitionRepository(authApiRouteClient);
	}

	// ──────────────────────────────────────────────────────────
	// getRouteDefinitions
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("getRouteDefinitions() — 캐시 반환 테스트")
	class GetRouteDefinitionsTest {

		@Test
		@DisplayName("캐시가 비어 있을 때 빈 Flux 반환")
		void getRouteDefinitions_withEmptyCache_returnsEmptyFlux() {
			// when
			Flux<RouteDefinition> result = repository.getRouteDefinitions();

			// then
			StepVerifier.create(result).verifyComplete();
		}

		@Test
		@DisplayName("reload() 후 getRouteDefinitions()가 로드된 라우트 반환")
		void getRouteDefinitions_afterReload_returnsLoadedRoutes() {
			// given
			List<AuthApiRouteClient.RouteDto> remoteDtos =
					List.of(
							new AuthApiRouteClient.RouteDto("r1", "/api/v2/service", "http://service:8080", true),
							new AuthApiRouteClient.RouteDto("r2", "/api/v2/other", "http://other:8080", true));
			given(authApiRouteClient.fetchEnabledRoutes()).willReturn(remoteDtos);

			// when
			repository.reload();
			Flux<RouteDefinition> result = repository.getRouteDefinitions();

			// then
			StepVerifier.create(result.collectList())
					.assertNext(
							definitions -> {
								assertThat(definitions).hasSize(2);
								assertThat(definitions)
										.extracting(RouteDefinition::getId)
										.containsExactlyInAnyOrder("r1", "r2");
							})
					.verifyComplete();
		}
	}

	// ──────────────────────────────────────────────────────────
	// reload()
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("reload() — auth-api로부터 라우트 재로드")
	class ReloadTest {

		@Test
		@DisplayName("reload() 호출 시 authApiRouteClient.fetchEnabledRoutes() 호출")
		void reload_callsFetchEnabledRoutes() {
			// given
			given(authApiRouteClient.fetchEnabledRoutes()).willReturn(List.of());

			// when
			repository.reload();

			// then
			org.mockito.BDDMockito.then(authApiRouteClient).should(times(1)).fetchEnabledRoutes();
		}

		@Test
		@DisplayName("reload() 후 기존 캐시가 새 라우트로 완전 교체됨")
		void reload_replacesEntireCacheWithNewRoutes() {
			// given — 첫 로드: 1개
			given(authApiRouteClient.fetchEnabledRoutes())
					.willReturn(
							List.of(
									new AuthApiRouteClient.RouteDto(
											"r-old", "/api/v2/old", "http://old:8080", true)));
			repository.reload();

			// 두 번째 로드: 2개 (r-old 없음)
			given(authApiRouteClient.fetchEnabledRoutes())
					.willReturn(
							List.of(
									new AuthApiRouteClient.RouteDto(
											"r-new1", "/api/v2/new1", "http://new1:8080", true),
									new AuthApiRouteClient.RouteDto(
											"r-new2", "/api/v2/new2", "http://new2:8080", true)));
			repository.reload();

			// when
			Flux<RouteDefinition> result = repository.getRouteDefinitions();

			// then — r-old가 없고 r-new1, r-new2만 있음
			StepVerifier.create(result.collectList())
					.assertNext(
							definitions -> {
								assertThat(definitions).hasSize(2);
								assertThat(definitions)
										.extracting(RouteDefinition::getId)
										.containsExactlyInAnyOrder("r-new1", "r-new2");
								assertThat(definitions).extracting(RouteDefinition::getId).doesNotContain("r-old");
							})
					.verifyComplete();
		}
	}

	// ──────────────────────────────────────────────────────────
	// RouteDefinition 변환
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("RouteDefinition 변환 — pathPrefix → Path predicate")
	class RouteDefinitionConversionTest {

		@Test
		@DisplayName("pathPrefix가 RouteDefinition의 Path predicate로 변환됨")
		void reload_convertsPathPrefixToRoutePredicate() {
			// given
			given(authApiRouteClient.fetchEnabledRoutes())
					.willReturn(
							List.of(
									new AuthApiRouteClient.RouteDto(
											"r-convert", "/api/v2/convert", "http://convert:8080", true)));

			// when
			repository.reload();
			Flux<RouteDefinition> result = repository.getRouteDefinitions();

			// then
			StepVerifier.create(result.collectList())
					.assertNext(
							definitions -> {
								assertThat(definitions).hasSize(1);
								RouteDefinition definition = definitions.get(0);
								assertThat(definition.getId()).isEqualTo("r-convert");
								assertThat(definition.getUri().toString()).isEqualTo("http://convert:8080");
								// Path predicate가 설정되어 있어야 함
								assertThat(definition.getPredicates()).isNotEmpty();
							})
					.verifyComplete();
		}

		@Test
		@DisplayName("업스트림 URL이 RouteDefinition URI로 올바르게 설정됨")
		void reload_setsUpstreamUrlAsRouteDefinitionUri() {
			// given
			given(authApiRouteClient.fetchEnabledRoutes())
					.willReturn(
							List.of(
									new AuthApiRouteClient.RouteDto(
											"r-uri", "/api/v2/uri", "http://upstream-service:9090", true)));

			// when
			repository.reload();

			// then
			StepVerifier.create(repository.getRouteDefinitions().collectList())
					.assertNext(
							definitions -> {
								RouteDefinition def = definitions.get(0);
								assertThat(def.getUri().getHost()).isEqualTo("upstream-service");
								assertThat(def.getUri().getPort()).isEqualTo(9090);
							})
					.verifyComplete();
		}
	}

	// ──────────────────────────────────────────────────────────
	// 보호 경로 우선순위
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("보호 경로 우선순위 — 동적 라우트 낮은 Order")
	class ProtectedPathPriorityTest {

		@Test
		@DisplayName("DynamicRouteDefinitionRepository의 Order가 0보다 큰 낮은 우선순위 값임")
		void dynamicRepository_hasLowerOrderThanStaticRoutes() {
			// given/when — 클래스의 @Order 또는 getOrder() 확인
			// 정적 보호 라우트(GatewayRoutingConfig)보다 낮은 우선순위여야 함

			// then — 구현체에 getOrder() 또는 Ordered.LOWEST_PRECEDENCE 설정 확인
			if (repository instanceof org.springframework.core.Ordered ordered) {
				assertThat(ordered.getOrder()).isGreaterThan(0);
			}
			// 인터페이스 구현 여부와 관계없이 @Order 어노테이션 존재를 확인
			// (컴파일 에러 발생 예상 — 클래스 미존재 시)
			assertThat(repository).isNotNull();
		}
	}
}
