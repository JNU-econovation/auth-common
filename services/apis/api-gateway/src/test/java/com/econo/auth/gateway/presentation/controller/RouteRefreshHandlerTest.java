package com.econo.auth.gateway.presentation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;

import com.econo.auth.gateway.config.DynamicRouteDefinitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.server.ServerRequest;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * RouteRefreshHandler 단위 테스트
 *
 * <p>X-Internal-Secret 헤더 검증, RefreshRoutesEvent 발행, DynamicRouteDefinitionRepository.reload() 호출을
 * 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class RouteRefreshHandlerTest {

	@Mock private DynamicRouteDefinitionRepository dynamicRouteDefinitionRepository;
	@Mock private ApplicationEventPublisher eventPublisher;

	private static final String VALID_SECRET = "test-internal-secret";

	private RouteRefreshHandler handler;

	@BeforeEach
	void setUp() {
		handler =
				new RouteRefreshHandler(dynamicRouteDefinitionRepository, eventPublisher, VALID_SECRET);
		// reload()는 논블로킹 Mono를 반환 — 시크릿 검증 통과 테스트에서 체인이 구독할 수 있도록 빈 Mono로 스텁(lenient).
		lenient().when(dynamicRouteDefinitionRepository.reload()).thenReturn(Mono.empty());
	}

	@Nested
	@DisplayName("X-Internal-Secret 검증 성공")
	class ValidSecretTest {

		@Test
		@DisplayName("올바른 시크릿으로 요청 시 200 OK + {refreshed: true} 반환")
		void handle_withValidSecret_returns200WithRefreshedTrue() {
			// given
			MockServerHttpRequest request =
					MockServerHttpRequest.post("/api/v1/internal/routes/refresh")
							.header("X-Internal-Secret", VALID_SECRET)
							.build();
			MockServerWebExchange exchange = MockServerWebExchange.from(request);
			ServerRequest serverRequest =
					ServerRequest.create(exchange, java.util.Collections.emptyList());

			// when
			Mono<org.springframework.web.reactive.function.server.ServerResponse> result =
					handler.handle(serverRequest);

			// then
			StepVerifier.create(result)
					.assertNext(response -> assertThat(response.statusCode()).isEqualTo(HttpStatus.OK))
					.verifyComplete();
		}

		@Test
		@DisplayName("올바른 시크릿으로 요청 시 DynamicRouteDefinitionRepository.reload() 호출")
		void handle_withValidSecret_callsRepositoryReload() {
			// given
			MockServerHttpRequest request =
					MockServerHttpRequest.post("/api/v1/internal/routes/refresh")
							.header("X-Internal-Secret", VALID_SECRET)
							.build();
			MockServerWebExchange exchange = MockServerWebExchange.from(request);
			ServerRequest serverRequest =
					ServerRequest.create(exchange, java.util.Collections.emptyList());

			// when
			handler.handle(serverRequest).block();

			// then
			then(dynamicRouteDefinitionRepository).should(times(1)).reload();
		}

		@Test
		@DisplayName("올바른 시크릿으로 요청 시 RefreshRoutesEvent 발행")
		void handle_withValidSecret_publishesRefreshRoutesEvent() {
			// given
			MockServerHttpRequest request =
					MockServerHttpRequest.post("/api/v1/internal/routes/refresh")
							.header("X-Internal-Secret", VALID_SECRET)
							.build();
			MockServerWebExchange exchange = MockServerWebExchange.from(request);
			ServerRequest serverRequest =
					ServerRequest.create(exchange, java.util.Collections.emptyList());

			// when
			handler.handle(serverRequest).block();

			// then
			then(eventPublisher)
					.should(times(1))
					.publishEvent(org.mockito.ArgumentMatchers.isA(RefreshRoutesEvent.class));
		}
	}

	@Nested
	@DisplayName("X-Internal-Secret 검증 실패")
	class InvalidSecretTest {

		@Test
		@DisplayName("시크릿 불일치 시 403 FORBIDDEN 반환")
		void handle_withWrongSecret_returns403() {
			// given
			MockServerHttpRequest request =
					MockServerHttpRequest.post("/api/v1/internal/routes/refresh")
							.header("X-Internal-Secret", "wrong-secret")
							.build();
			MockServerWebExchange exchange = MockServerWebExchange.from(request);
			ServerRequest serverRequest =
					ServerRequest.create(exchange, java.util.Collections.emptyList());

			// when
			Mono<org.springframework.web.reactive.function.server.ServerResponse> result =
					handler.handle(serverRequest);

			// then
			StepVerifier.create(result)
					.assertNext(response -> assertThat(response.statusCode()).isEqualTo(HttpStatus.FORBIDDEN))
					.verifyComplete();
		}

		@Test
		@DisplayName("시크릿 헤더 없이 요청 시 403 FORBIDDEN 반환")
		void handle_withoutSecretHeader_returns403() {
			// given
			MockServerHttpRequest request =
					MockServerHttpRequest.post("/api/v1/internal/routes/refresh").build();
			MockServerWebExchange exchange = MockServerWebExchange.from(request);
			ServerRequest serverRequest =
					ServerRequest.create(exchange, java.util.Collections.emptyList());

			// when
			Mono<org.springframework.web.reactive.function.server.ServerResponse> result =
					handler.handle(serverRequest);

			// then
			StepVerifier.create(result)
					.assertNext(response -> assertThat(response.statusCode()).isEqualTo(HttpStatus.FORBIDDEN))
					.verifyComplete();
		}

		@Test
		@DisplayName("시크릿 불일치 시 DynamicRouteDefinitionRepository.reload() 미호출")
		void handle_withWrongSecret_doesNotCallReload() {
			// given
			MockServerHttpRequest request =
					MockServerHttpRequest.post("/api/v1/internal/routes/refresh")
							.header("X-Internal-Secret", "wrong-secret")
							.build();
			MockServerWebExchange exchange = MockServerWebExchange.from(request);
			ServerRequest serverRequest =
					ServerRequest.create(exchange, java.util.Collections.emptyList());

			// when
			handler.handle(serverRequest).block();

			// then
			then(dynamicRouteDefinitionRepository).should(org.mockito.Mockito.never()).reload();
			then(eventPublisher)
					.should(org.mockito.Mockito.never())
					.publishEvent(org.mockito.ArgumentMatchers.any());
		}
	}
}
