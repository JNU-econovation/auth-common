package com.econo.auth.client.application.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** ServiceRoute 도메인 객체 단위 테스트 */
class ServiceRouteTest {

	@Nested
	@DisplayName("ServiceRoute 생성 테스트")
	class CreationTest {

		@Test
		@DisplayName("전체 필드 canonical constructor — 모든 필드 정상 설정")
		void canonicalConstructor_setsAllFields() {
			// given
			LocalDateTime now = LocalDateTime.now();
			LocalDateTime later = now.plusMinutes(1);

			// when
			ServiceRoute route =
					new ServiceRoute("uuid-1234", "/api/v2/service", "http://service:8080", true, now, later);

			// then
			assertThat(route.routeId()).isEqualTo("uuid-1234");
			assertThat(route.pathPrefix()).isEqualTo("/api/v2/service");
			assertThat(route.upstreamUrl()).isEqualTo("http://service:8080");
			assertThat(route.enabled()).isTrue();
			assertThat(route.createdAt()).isEqualTo(now);
			assertThat(route.updatedAt()).isEqualTo(later);
		}

		@Test
		@DisplayName("ServiceRoute.create() — routeId가 UUID 형식으로 생성됨")
		void staticCreate_generatesUuidRouteId() {
			// when
			ServiceRoute route = ServiceRoute.create("/api/v2/new", "http://new:8080", true);

			// then
			assertThat(route.routeId()).isNotBlank();
			assertThat(route.routeId())
					.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
		}

		@Test
		@DisplayName("ServiceRoute.create() — pathPrefix, upstreamUrl, enabled 정상 설정")
		void staticCreate_setsPathPrefixAndUpstreamAndEnabled() {
			// when
			ServiceRoute route = ServiceRoute.create("/api/v2/test", "https://test:9090", false);

			// then
			assertThat(route.pathPrefix()).isEqualTo("/api/v2/test");
			assertThat(route.upstreamUrl()).isEqualTo("https://test:9090");
			assertThat(route.enabled()).isFalse();
		}

		@Test
		@DisplayName("ServiceRoute.create() — 매 호출마다 서로 다른 routeId 생성 (UUID 유일성)")
		void staticCreate_eachCallProducesUniqueRouteId() {
			// when
			ServiceRoute route1 = ServiceRoute.create("/api/v2/a", "http://a:8080", true);
			ServiceRoute route2 = ServiceRoute.create("/api/v2/b", "http://b:8080", true);

			// then
			assertThat(route1.routeId()).isNotEqualTo(route2.routeId());
		}
	}

	@Nested
	@DisplayName("ServiceRoute 불변성 테스트")
	class ImmutabilityTest {

		@Test
		@DisplayName("record이므로 필드 변경 불가 — 컴파일 레벨 불변성")
		void record_isImmutable() {
			// given
			ServiceRoute route =
					new ServiceRoute(
							"uuid-immutable",
							"/api/v2/immutable",
							"http://immutable:8080",
							true,
							LocalDateTime.now(),
							LocalDateTime.now());

			// then — record accessor만 존재, setter 없음
			assertThat(route.routeId()).isEqualTo("uuid-immutable");
			// record에는 setter가 없으므로 이 테스트가 컴파일되면 불변성 보장
		}
	}
}
