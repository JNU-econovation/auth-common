package com.econo.common.auth.core.passport;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class PassportExceptionTest {

	@Nested
	@DisplayName("정적 팩토리 메서드 테스트")
	class StaticFactoryMethodTest {

		@Test
		@DisplayName("unauthorized 예외 생성")
		void unauthorized() {
			// given
			String message = "Authentication required";

			// when
			PassportException exception = PassportException.unauthorized(message);

			// then
			assertThat(exception.getHttpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
			assertThat(exception.getErrorCode()).isEqualTo("AUTH_UNAUTHORIZED");
			assertThat(exception.getMessage()).isEqualTo(message);
		}

		@Test
		@DisplayName("forbidden 예외 생성")
		void forbidden() {
			// given
			String message = "Access denied";

			// when
			PassportException exception = PassportException.forbidden(message);

			// then
			assertThat(exception.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
			assertThat(exception.getErrorCode()).isEqualTo("AUTH_FORBIDDEN");
			assertThat(exception.getMessage()).isEqualTo(message);
		}

		@Test
		@DisplayName("badRequest 예외 생성")
		void badRequest() {
			// given
			String message = "Invalid request";

			// when
			PassportException exception = PassportException.badRequest(message);

			// then
			assertThat(exception.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
			assertThat(exception.getErrorCode()).isEqualTo("AUTH_BAD_REQUEST");
			assertThat(exception.getMessage()).isEqualTo(message);
		}

		@Test
		@DisplayName("expired 예외 생성")
		void expired() {
			// given
			Long memberId = 123L;

			// when
			PassportException exception = PassportException.expired(memberId);

			// then
			assertThat(exception.getHttpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
			assertThat(exception.getErrorCode()).isEqualTo("AUTH_TOKEN_EXPIRED");
			assertThat(exception.getMessage()).contains("123");
		}

		@Test
		@DisplayName("invalid 예외 생성")
		void invalid() {
			// given
			String reason = "passport validation failed";

			// when
			PassportException exception = PassportException.invalid(reason);

			// then
			assertThat(exception.getHttpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
			assertThat(exception.getErrorCode()).isEqualTo("AUTH_PASSPORT_INVALID");
			assertThat(exception.getMessage()).contains(reason);
		}
	}

	@Nested
	@DisplayName("예외 상태 검증 테스트")
	class StatusValidationTest {

		@Test
		@DisplayName("401 Unauthorized 상태 확인")
		void unauthorizedStatus() {
			PassportException unauthorized = PassportException.unauthorized("test");
			PassportException expired = PassportException.expired(123L);
			PassportException invalid = PassportException.invalid("test");

			assertThat(unauthorized.getHttpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
			assertThat(expired.getHttpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
			assertThat(invalid.getHttpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
		}

		@Test
		@DisplayName("403 Forbidden 상태 확인")
		void forbiddenStatus() {
			PassportException forbidden = PassportException.forbidden("test");

			assertThat(forbidden.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
		}

		@Test
		@DisplayName("400 Bad Request 상태 확인")
		void badRequestStatus() {
			PassportException badRequest = PassportException.badRequest("test");

			assertThat(badRequest.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
		}
	}

	@Nested
	@DisplayName("에러 코드 테스트")
	class ErrorCodeTest {

		@Test
		@DisplayName("각 예외별 고유한 에러 코드")
		void uniqueErrorCodes() {
			PassportException unauthorized = PassportException.unauthorized("test");
			PassportException forbidden = PassportException.forbidden("test");
			PassportException badRequest = PassportException.badRequest("test");
			PassportException expired = PassportException.expired(123L);
			PassportException invalid = PassportException.invalid("test");

			assertThat(unauthorized.getErrorCode()).isEqualTo("AUTH_UNAUTHORIZED");
			assertThat(forbidden.getErrorCode()).isEqualTo("AUTH_FORBIDDEN");
			assertThat(badRequest.getErrorCode()).isEqualTo("AUTH_BAD_REQUEST");
			assertThat(expired.getErrorCode()).isEqualTo("AUTH_TOKEN_EXPIRED");
			assertThat(invalid.getErrorCode()).isEqualTo("AUTH_PASSPORT_INVALID");
		}

		@Test
		@DisplayName("모든 에러 코드가 AUTH_ 접두사를 가짐")
		void errorCodePrefix() {
			PassportException unauthorized = PassportException.unauthorized("test");
			PassportException forbidden = PassportException.forbidden("test");
			PassportException badRequest = PassportException.badRequest("test");
			PassportException expired = PassportException.expired(123L);
			PassportException invalid = PassportException.invalid("test");

			assertThat(unauthorized.getErrorCode()).startsWith("AUTH_");
			assertThat(forbidden.getErrorCode()).startsWith("AUTH_");
			assertThat(badRequest.getErrorCode()).startsWith("AUTH_");
			assertThat(expired.getErrorCode()).startsWith("AUTH_");
			assertThat(invalid.getErrorCode()).startsWith("AUTH_");
		}
	}

	@Nested
	@DisplayName("메시지 형식 테스트")
	class MessageFormatTest {

		@Test
		@DisplayName("expired 예외 메시지에 memberId 포함")
		void expiredMessageContainsMemberId() {
			Long memberId = 12345L;
			PassportException exception = PassportException.expired(memberId);

			assertThat(exception.getMessage()).contains("12345").containsIgnoringCase("expired");
		}

		@Test
		@DisplayName("null memberId로 expired 예외 생성")
		void expiredWithNullMemberId() {
			PassportException exception = PassportException.expired(null);

			assertThat(exception.getMessage()).contains("null").containsIgnoringCase("expired");
		}

		@Test
		@DisplayName("커스텀 메시지가 포함됨")
		void customMessagePreserved() {
			String customMessage = "Custom error message";

			PassportException unauthorized = PassportException.unauthorized(customMessage);
			PassportException forbidden = PassportException.forbidden(customMessage);
			PassportException badRequest = PassportException.badRequest(customMessage);
			PassportException invalid = PassportException.invalid(customMessage);

			assertThat(unauthorized.getMessage()).isEqualTo(customMessage);
			assertThat(forbidden.getMessage()).isEqualTo(customMessage);
			assertThat(badRequest.getMessage()).isEqualTo(customMessage);
			assertThat(invalid.getMessage()).contains(customMessage);
		}
	}

	@Nested
	@DisplayName("예외 타입 확인 테스트")
	class ExceptionTypeTest {

		@Test
		@DisplayName("모든 예외가 RuntimeException을 상속")
		void extendsRuntimeException() {
			PassportException exception = PassportException.unauthorized("test");

			assertThat(exception).isInstanceOf(RuntimeException.class);
		}

		@Test
		@DisplayName("예외 타입별 구분 가능")
		void distinguishableExceptionTypes() {
			PassportException unauthorized = PassportException.unauthorized("test");
			PassportException forbidden = PassportException.forbidden("test");
			PassportException badRequest = PassportException.badRequest("test");
			PassportException expired = PassportException.expired(123L);
			PassportException invalid = PassportException.invalid("test");

			// HTTP 상태로 구분
			assertThat(unauthorized.getHttpStatus()).isNotEqualTo(forbidden.getHttpStatus());
			assertThat(forbidden.getHttpStatus()).isNotEqualTo(badRequest.getHttpStatus());

			// 에러 코드로 구분
			assertThat(unauthorized.getErrorCode()).isNotEqualTo(forbidden.getErrorCode());
			assertThat(expired.getErrorCode()).isNotEqualTo(invalid.getErrorCode());
		}
	}

	@Nested
	@DisplayName("통합 시나리오 테스트")
	class IntegrationScenarioTest {

		@Test
		@DisplayName("인증 실패 시나리오")
		void authenticationFailureScenario() {
			// 헤더 없음
			PassportException noHeader = PassportException.unauthorized("Missing authentication header");
			assertThat(noHeader.getHttpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
			assertThat(noHeader.getErrorCode()).isEqualTo("AUTH_UNAUTHORIZED");

			// 잘못된 형식
			PassportException invalidFormat = PassportException.badRequest("Invalid passport format");
			assertThat(invalidFormat.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
			assertThat(invalidFormat.getErrorCode()).isEqualTo("AUTH_BAD_REQUEST");

			// 만료된 토큰
			PassportException expiredToken = PassportException.expired(123L);
			assertThat(expiredToken.getHttpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
			assertThat(expiredToken.getErrorCode()).isEqualTo("AUTH_TOKEN_EXPIRED");
		}

		@Test
		@DisplayName("권한 부족 시나리오")
		void authorizationFailureScenario() {
			PassportException forbidden =
					PassportException.forbidden("Insufficient permissions for admin area");

			assertThat(forbidden.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
			assertThat(forbidden.getErrorCode()).isEqualTo("AUTH_FORBIDDEN");
			assertThat(forbidden.getMessage()).contains("Insufficient permissions");
		}

		@Test
		@DisplayName("SpEL 조건 실패 시나리오")
		void spelConditionFailureScenario() {
			PassportException conditionFailed =
					PassportException.forbidden(
							"Member 123 does not meet condition: #passport.memberId == #userId");

			assertThat(conditionFailed.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
			assertThat(conditionFailed.getErrorCode()).isEqualTo("AUTH_FORBIDDEN");
			assertThat(conditionFailed.getMessage()).contains("does not meet condition");
		}
	}
}
