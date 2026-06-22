package com.econo.auth.client.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.econo.auth.client.exception.RouteNamespaceInvalidException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * RouteNamespaceExtractor 단위 테스트
 *
 * <p>커버 범위:
 *
 * <ul>
 *   <li>/api/eeos/** → "eeos" 추출
 *   <li>/api/eeos/v1/** → "eeos" 추출 (두 번째 세그먼트만)
 *   <li>/api/my-service/v1/** → "my-service" 추출
 *   <li>/eeos/** → RouteNamespaceInvalidException (첫 세그먼트가 "api" 아님)
 *   <li>/api/** → RouteNamespaceInvalidException (두 번째 세그먼트 없음/와일드카드)
 *   <li>/api/ → RouteNamespaceInvalidException (두 번째 세그먼트 비어있음)
 *   <li>null / blank → RouteNamespaceInvalidException
 * </ul>
 */
class RouteNamespaceExtractorTest {

	private RouteNamespaceExtractor extractor;

	@BeforeEach
	void setUp() {
		extractor = new RouteNamespaceExtractor();
	}

	// ──────────────────────────────────────────────────────────
	// 유효한 네임스페이스 추출
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("유효한 pathPrefix에서 네임스페이스 추출")
	class ValidNamespaceExtractionTest {

		@Test
		@DisplayName("/api/eeos/** → 두 번째 세그먼트 'eeos' 반환")
		void extract_withApiEeosWildcard_returnsEeos() {
			// when
			String namespace = extractor.extract("/api/eeos/**");

			// then
			assertThat(namespace).isEqualTo("eeos");
		}

		@Test
		@DisplayName("/api/eeos/v1/** → 두 번째 세그먼트 'eeos' 반환 (세 번째 세그먼트 무시)")
		void extract_withApiEeosV1Wildcard_returnsEeos() {
			// when
			String namespace = extractor.extract("/api/eeos/v1/**");

			// then
			assertThat(namespace).isEqualTo("eeos");
		}

		@Test
		@DisplayName("/api/my-service → 두 번째 세그먼트 'my-service' 반환")
		void extract_withApiMyService_returnsMyService() {
			// when
			String namespace = extractor.extract("/api/my-service");

			// then
			assertThat(namespace).isEqualTo("my-service");
		}

		@Test
		@DisplayName("/api/my-service/v1/** → 두 번째 세그먼트 'my-service' 반환")
		void extract_withApiMyServiceV1Wildcard_returnsMyService() {
			// when
			String namespace = extractor.extract("/api/my-service/v1/**");

			// then
			assertThat(namespace).isEqualTo("my-service");
		}

		@Test
		@DisplayName("/api/eeos (슬래시 없이 끝나는 형태) → 'eeos' 반환")
		void extract_withApiEeosNoTrailingSlash_returnsEeos() {
			// when
			String namespace = extractor.extract("/api/eeos");

			// then
			assertThat(namespace).isEqualTo("eeos");
		}

		@Test
		@DisplayName("/api/EEOS → 대소문자 그대로 'EEOS' 반환 (소문자 강제 없음)")
		void extract_withUpperCaseNamespace_returnsOriginalCase() {
			// when
			String namespace = extractor.extract("/api/EEOS");

			// then
			assertThat(namespace).isEqualTo("EEOS");
		}
	}

	// ──────────────────────────────────────────────────────────
	// 유효하지 않은 pathPrefix — 예외 발생
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("유효하지 않은 pathPrefix → RouteNamespaceInvalidException 발생")
	class InvalidNamespaceTest {

		@Test
		@DisplayName("/eeos/** → 첫 세그먼트가 'api' 아님 → 예외 발생")
		void extract_withoutApiPrefix_throwsException() {
			// when / then
			assertThatThrownBy(() -> extractor.extract("/eeos/**"))
					.isInstanceOf(RouteNamespaceInvalidException.class);
		}

		@Test
		@DisplayName("/api/** → 두 번째 세그먼트가 '**'이므로 유효 네임스페이스 아님 → 예외 발생")
		void extract_withApiDoubleWildcard_throwsException() {
			// when / then
			assertThatThrownBy(() -> extractor.extract("/api/**"))
					.isInstanceOf(RouteNamespaceInvalidException.class);
		}

		@Test
		@DisplayName("/api/ → 두 번째 세그먼트 비어있음 → 예외 발생")
		void extract_withApiTrailingSlashOnly_throwsException() {
			// when / then
			assertThatThrownBy(() -> extractor.extract("/api/"))
					.isInstanceOf(RouteNamespaceInvalidException.class);
		}

		@Test
		@DisplayName("/api → 두 번째 세그먼트 없음 → 예외 발생")
		void extract_withApiOnly_throwsException() {
			// when / then
			assertThatThrownBy(() -> extractor.extract("/api"))
					.isInstanceOf(RouteNamespaceInvalidException.class);
		}

		@Test
		@DisplayName("null → 예외 발생")
		void extract_withNull_throwsException() {
			// when / then
			assertThatThrownBy(() -> extractor.extract(null))
					.isInstanceOf(RouteNamespaceInvalidException.class);
		}

		@Test
		@DisplayName("빈 문자열 → 예외 발생")
		void extract_withBlank_throwsException() {
			// when / then
			assertThatThrownBy(() -> extractor.extract(""))
					.isInstanceOf(RouteNamespaceInvalidException.class);
		}

		@Test
		@DisplayName("공백 문자열 → 예외 발생")
		void extract_withWhitespace_throwsException() {
			// when / then
			assertThatThrownBy(() -> extractor.extract("   "))
					.isInstanceOf(RouteNamespaceInvalidException.class);
		}

		@Test
		@DisplayName("/ (루트만) → 예외 발생")
		void extract_withRootOnly_throwsException() {
			// when / then
			assertThatThrownBy(() -> extractor.extract("/"))
					.isInstanceOf(RouteNamespaceInvalidException.class);
		}

		@Test
		@DisplayName("/other/eeos → 첫 세그먼트 'other'이므로 예외 발생")
		void extract_withNonApiFirstSegment_throwsException() {
			// when / then
			assertThatThrownBy(() -> extractor.extract("/other/eeos"))
					.isInstanceOf(RouteNamespaceInvalidException.class);
		}
	}
}
