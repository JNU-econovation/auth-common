package com.econo.common.auth.web.resolver;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.econo.common.auth.core.passport.Passport;
import com.econo.common.auth.core.passport.PassportException;
import com.econo.common.auth.web.annotation.PassportAuth;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.web.context.request.NativeWebRequest;

@ExtendWith(MockitoExtension.class)
class PassportArgumentResolverTest {

	@Mock private NativeWebRequest webRequest;

	@Mock private HttpServletRequest httpRequest;

	@Mock private MethodParameter methodParameter;

	private ObjectMapper objectMapper;
	private PassportArgumentResolver resolver;

	@BeforeEach
	void setUp() {
		objectMapper = new ObjectMapper();
		objectMapper.registerModule(new JavaTimeModule());
		resolver = new PassportArgumentResolver(objectMapper);
	}

	@Nested
	class SupportsParameterTest {

		@Test
		@DisplayName("@PassportAuth와 Passport 타입이면 지원")
		void supportsPassportAuthAnnotationWithPassportType() {
			// given
			when(methodParameter.hasParameterAnnotation(PassportAuth.class)).thenReturn(true);
			when(methodParameter.getParameterType()).thenReturn((Class) Passport.class);

			// when
			boolean result = resolver.supportsParameter(methodParameter);

			// then
			assertThat(result).isTrue();
		}

		@Test
		@DisplayName("@PassportAuth가 없으면 지원하지 않음")
		void doesNotSupportWithoutPassportAuth() {
			// given
			when(methodParameter.hasParameterAnnotation(PassportAuth.class)).thenReturn(false);

			// when
			boolean result = resolver.supportsParameter(methodParameter);

			// then
			assertThat(result).isFalse();
		}

		@Test
		@DisplayName("Passport 타입이 아니면 지원하지 않음")
		void doesNotSupportNonPassportType() {
			// given
			when(methodParameter.hasParameterAnnotation(PassportAuth.class)).thenReturn(true);
			when(methodParameter.getParameterType()).thenReturn((Class) String.class);

			// when
			boolean result = resolver.supportsParameter(methodParameter);

			// then
			assertThat(result).isFalse();
		}
	}

	@Nested
	@DisplayName("resolveArgument 테스트")
	class ResolveArgumentTest {

		private PassportAuth createPassportAuth(
				boolean required,
				String[] roles,
				boolean requireAllRoles,
				boolean includeHigherRoles,
				String condition) {
			return new PassportAuth() {
				@Override
				public Class<? extends java.lang.annotation.Annotation> annotationType() {
					return PassportAuth.class;
				}

				@Override
				public boolean required() {
					return required;
				}

				@Override
				public boolean validateExpiry() {
					return true;
				}

				@Override
				public String[] requiredRoles() {
					return roles;
				}

				@Override
				public boolean requireAllRoles() {
					return requireAllRoles;
				}

				@Override
				public boolean includeHigherRoles() {
					return includeHigherRoles;
				}

				@Override
				public String condition() {
					return condition;
				}
			};
		}

		private String createEncodedPassport(Passport passport) throws Exception {
			String json = objectMapper.writeValueAsString(passport);
			return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
		}

		@Test
		@DisplayName("정상적인 Passport 해결")
		void resolveValidPassport() throws Exception {
			// given
			LocalDateTime now = LocalDateTime.now();
			Passport passport =
					new Passport(123L, "test@eeos.com", "테스터", List.of("USER"), now, now.plusHours(1));
			String encodedPassport = createEncodedPassport(passport);

			PassportAuth annotation = createPassportAuth(true, new String[] {}, false, false, "");

			when(methodParameter.getParameterAnnotation(PassportAuth.class)).thenReturn(annotation);
			when(webRequest.getNativeRequest(HttpServletRequest.class)).thenReturn(httpRequest);
			when(httpRequest.getHeader("X-User-Passport")).thenReturn(encodedPassport);

			// when
			Object result = resolver.resolveArgument(methodParameter, null, webRequest, null);

			// then
			assertThat(result).isInstanceOf(Passport.class);
			Passport resolvedPassport = (Passport) result;
			assertThat(resolvedPassport.getMemberId()).isEqualTo(123L);
			assertThat(resolvedPassport.getEmail()).isEqualTo("test@eeos.com");
			assertThat(resolvedPassport.getName()).isEqualTo("테스터");
		}

		@Test
		@DisplayName("선택적 인증에서 헤더가 없으면 null 반환")
		void optionalAuthWithoutHeader() {
			// given
			PassportAuth annotation = createPassportAuth(false, new String[] {}, false, false, "");

			when(methodParameter.getParameterAnnotation(PassportAuth.class)).thenReturn(annotation);
			when(webRequest.getNativeRequest(HttpServletRequest.class)).thenReturn(httpRequest);
			when(httpRequest.getHeader("X-User-Passport")).thenReturn(null);

			// when
			Object result = resolver.resolveArgument(methodParameter, null, webRequest, null);

			// then
			assertThat(result).isNull();
		}

		@Test
		@DisplayName("필수 인증에서 헤더가 없으면 예외 발생")
		void requiredAuthWithoutHeader() {
			// given
			PassportAuth annotation = createPassportAuth(true, new String[] {}, false, false, "");

			when(methodParameter.getParameterAnnotation(PassportAuth.class)).thenReturn(annotation);
			when(webRequest.getNativeRequest(HttpServletRequest.class)).thenReturn(httpRequest);
			when(httpRequest.getHeader("X-User-Passport")).thenReturn(null);

			// when & then
			assertThatThrownBy(() -> resolver.resolveArgument(methodParameter, null, webRequest, null))
					.isInstanceOf(PassportException.class)
					.hasMessageContaining("Authentication required");
		}

		@Test
		@DisplayName("잘못된 형식의 헤더로 예외 발생")
		void invalidHeaderFormat() {
			// given
			PassportAuth annotation = createPassportAuth(true, new String[] {}, false, false, "");

			when(methodParameter.getParameterAnnotation(PassportAuth.class)).thenReturn(annotation);
			when(webRequest.getNativeRequest(HttpServletRequest.class)).thenReturn(httpRequest);
			when(httpRequest.getHeader("X-User-Passport")).thenReturn("invalid-base64");

			// when & then
			assertThatThrownBy(() -> resolver.resolveArgument(methodParameter, null, webRequest, null))
					.isInstanceOf(PassportException.class)
					.hasMessageContaining("Failed to decode passport");
		}

		@Test
		@DisplayName("만료된 Passport로 예외 발생")
		void expiredPassport() throws Exception {
			// given
			LocalDateTime now = LocalDateTime.now();
			Passport passport =
					new Passport(
							123L, "test@eeos.com", "테스터", List.of("USER"), now.minusHours(2), now.minusHours(1));
			String encodedPassport = createEncodedPassport(passport);

			PassportAuth annotation = createPassportAuth(true, new String[] {}, false, false, "");

			when(methodParameter.getParameterAnnotation(PassportAuth.class)).thenReturn(annotation);
			when(webRequest.getNativeRequest(HttpServletRequest.class)).thenReturn(httpRequest);
			when(httpRequest.getHeader("X-User-Passport")).thenReturn(encodedPassport);

			// when & then
			assertThatThrownBy(() -> resolver.resolveArgument(methodParameter, null, webRequest, null))
					.isInstanceOf(PassportException.class)
					.hasMessageContaining("Expired passport");
		}
	}

	@Nested
	@DisplayName("권한 검증 테스트")
	class RoleValidationTest {

		private PassportAuth createPassportAuth(
				String[] roles, boolean requireAllRoles, boolean includeHigherRoles) {
			return new PassportAuth() {
				@Override
				public Class<? extends java.lang.annotation.Annotation> annotationType() {
					return PassportAuth.class;
				}

				@Override
				public boolean required() {
					return true;
				}

				@Override
				public boolean validateExpiry() {
					return true;
				}

				@Override
				public String[] requiredRoles() {
					return roles;
				}

				@Override
				public boolean requireAllRoles() {
					return requireAllRoles;
				}

				@Override
				public boolean includeHigherRoles() {
					return includeHigherRoles;
				}

				@Override
				public String condition() {
					return "";
				}
			};
		}

		@Test
		@DisplayName("필요한 권한이 있으면 성공")
		void hasRequiredRole() throws Exception {
			// given
			LocalDateTime now = LocalDateTime.now();
			Passport passport =
					new Passport(123L, "test@eeos.com", "테스터", List.of("ADMIN"), now, now.plusHours(1));
			String encodedPassport = createEncodedPassport(passport);

			PassportAuth annotation = createPassportAuth(new String[] {"ADMIN"}, false, false);

			when(methodParameter.getParameterAnnotation(PassportAuth.class)).thenReturn(annotation);
			when(webRequest.getNativeRequest(HttpServletRequest.class)).thenReturn(httpRequest);
			when(httpRequest.getHeader("X-User-Passport")).thenReturn(encodedPassport);

			// when
			Object result = resolver.resolveArgument(methodParameter, null, webRequest, null);

			// then
			assertThat(result).isNotNull();
		}

		@Test
		@DisplayName("필요한 권한이 없으면 예외 발생")
		void lacksRequiredRole() throws Exception {
			// given
			LocalDateTime now = LocalDateTime.now();
			Passport passport =
					new Passport(123L, "test@eeos.com", "테스터", List.of("USER"), now, now.plusHours(1));
			String encodedPassport = createEncodedPassport(passport);

			PassportAuth annotation = createPassportAuth(new String[] {"ADMIN"}, false, false);

			when(methodParameter.getParameterAnnotation(PassportAuth.class)).thenReturn(annotation);
			when(webRequest.getNativeRequest(HttpServletRequest.class)).thenReturn(httpRequest);
			when(httpRequest.getHeader("X-User-Passport")).thenReturn(encodedPassport);

			// when & then
			assertThatThrownBy(() -> resolver.resolveArgument(methodParameter, null, webRequest, null))
					.isInstanceOf(PassportException.class)
					.hasMessageContaining("lacks required roles");
		}

		@Test
		@DisplayName("권한 계층 지원 - ADMIN이 MANAGER 권한 만족")
		void roleHierarchySupport() throws Exception {
			// given
			LocalDateTime now = LocalDateTime.now();
			Passport passport =
					new Passport(123L, "test@eeos.com", "테스터", List.of("ADMIN"), now, now.plusHours(1));
			String encodedPassport = createEncodedPassport(passport);

			PassportAuth annotation = createPassportAuth(new String[] {"MANAGER"}, false, true);

			when(methodParameter.getParameterAnnotation(PassportAuth.class)).thenReturn(annotation);
			when(webRequest.getNativeRequest(HttpServletRequest.class)).thenReturn(httpRequest);
			when(httpRequest.getHeader("X-User-Passport")).thenReturn(encodedPassport);

			// when
			Object result = resolver.resolveArgument(methodParameter, null, webRequest, null);

			// then
			assertThat(result).isNotNull();
		}

		@Test
		@DisplayName("모든 권한 필요 - AND 조건")
		void requireAllRoles() throws Exception {
			// given
			LocalDateTime now = LocalDateTime.now();
			Passport passport =
					new Passport(
							123L, "test@eeos.com", "테스터", List.of("USER", "ACTIVE"), now, now.plusHours(1));
			String encodedPassport = createEncodedPassport(passport);

			PassportAuth annotation = createPassportAuth(new String[] {"USER", "ACTIVE"}, true, false);

			when(methodParameter.getParameterAnnotation(PassportAuth.class)).thenReturn(annotation);
			when(webRequest.getNativeRequest(HttpServletRequest.class)).thenReturn(httpRequest);
			when(httpRequest.getHeader("X-User-Passport")).thenReturn(encodedPassport);

			// when
			Object result = resolver.resolveArgument(methodParameter, null, webRequest, null);

			// then
			assertThat(result).isNotNull();
		}

		private String createEncodedPassport(Passport passport) throws Exception {
			String json = objectMapper.writeValueAsString(passport);
			return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
		}
	}

	@Nested
	@DisplayName("SpEL 조건 테스트")
	class SpELConditionTest {

		private PassportAuth createPassportAuthWithCondition(String condition) {
			return new PassportAuth() {
				@Override
				public Class<? extends java.lang.annotation.Annotation> annotationType() {
					return PassportAuth.class;
				}

				@Override
				public boolean required() {
					return true;
				}

				@Override
				public boolean validateExpiry() {
					return true;
				}

				@Override
				public String[] requiredRoles() {
					return new String[] {};
				}

				@Override
				public boolean requireAllRoles() {
					return false;
				}

				@Override
				public boolean includeHigherRoles() {
					return false;
				}

				@Override
				public String condition() {
					return condition;
				}
			};
		}

		@Test
		@DisplayName("SpEL 조건이 true면 성공")
		void spelConditionTrue() throws Exception {
			// given
			LocalDateTime now = LocalDateTime.now();
			Passport passport =
					new Passport(123L, "test@eeos.com", "테스터", List.of("USER"), now, now.plusHours(1));
			String encodedPassport = createEncodedPassport(passport);

			PassportAuth annotation = createPassportAuthWithCondition("#passport.memberId == 123");

			when(methodParameter.getParameterAnnotation(PassportAuth.class)).thenReturn(annotation);
			when(webRequest.getNativeRequest(HttpServletRequest.class)).thenReturn(httpRequest);
			when(httpRequest.getHeader("X-User-Passport")).thenReturn(encodedPassport);
			when(httpRequest.getAttribute(any())).thenReturn(null);
			when(httpRequest.getParameterMap()).thenReturn(Map.of());

			// when
			Object result = resolver.resolveArgument(methodParameter, null, webRequest, null);

			// then
			assertThat(result).isNotNull();
		}

		@Test
		@DisplayName("SpEL 조건이 false면 예외 발생")
		void spelConditionFalse() throws Exception {
			// given
			LocalDateTime now = LocalDateTime.now();
			Passport passport =
					new Passport(123L, "test@eeos.com", "테스터", List.of("USER"), now, now.plusHours(1));
			String encodedPassport = createEncodedPassport(passport);

			PassportAuth annotation = createPassportAuthWithCondition("#passport.memberId == 456");

			when(methodParameter.getParameterAnnotation(PassportAuth.class)).thenReturn(annotation);
			when(webRequest.getNativeRequest(HttpServletRequest.class)).thenReturn(httpRequest);
			when(httpRequest.getHeader("X-User-Passport")).thenReturn(encodedPassport);
			when(httpRequest.getAttribute(any())).thenReturn(null);
			when(httpRequest.getParameterMap()).thenReturn(Map.of());

			// when & then
			assertThatThrownBy(() -> resolver.resolveArgument(methodParameter, null, webRequest, null))
					.isInstanceOf(PassportException.class)
					.hasMessageContaining("does not meet condition");
		}

		@Test
		@DisplayName("Path variable을 사용한 SpEL 조건")
		void spelConditionWithPathVariable() throws Exception {
			// given
			LocalDateTime now = LocalDateTime.now();
			Passport passport =
					new Passport(123L, "test@eeos.com", "테스터", List.of("USER"), now, now.plusHours(1));
			String encodedPassport = createEncodedPassport(passport);

			PassportAuth annotation = createPassportAuthWithCondition("#passport.memberId == #userId");

			Map<String, String> pathVariables = new HashMap<>();
			pathVariables.put("userId", "123");

			when(methodParameter.getParameterAnnotation(PassportAuth.class)).thenReturn(annotation);
			when(webRequest.getNativeRequest(HttpServletRequest.class)).thenReturn(httpRequest);
			when(httpRequest.getHeader("X-User-Passport")).thenReturn(encodedPassport);
			when(httpRequest.getAttribute(any())).thenReturn(pathVariables);

			// when
			Object result = resolver.resolveArgument(methodParameter, null, webRequest, null);

			// then
			assertThat(result).isNotNull();
		}

		@Test
		@DisplayName("관리자 권한 확인 SpEL 조건")
		void spelConditionWithAdminCheck() throws Exception {
			// given
			LocalDateTime now = LocalDateTime.now();
			Passport passport =
					new Passport(123L, "test@eeos.com", "테스터", List.of("ADMIN"), now, now.plusHours(1));
			String encodedPassport = createEncodedPassport(passport);

			PassportAuth annotation = createPassportAuthWithCondition("#passport.isAdmin()");

			when(methodParameter.getParameterAnnotation(PassportAuth.class)).thenReturn(annotation);
			when(webRequest.getNativeRequest(HttpServletRequest.class)).thenReturn(httpRequest);
			when(httpRequest.getHeader("X-User-Passport")).thenReturn(encodedPassport);
			when(httpRequest.getAttribute(any())).thenReturn(null);
			when(httpRequest.getParameterMap()).thenReturn(Map.of());

			// when
			Object result = resolver.resolveArgument(methodParameter, null, webRequest, null);

			// then
			assertThat(result).isNotNull();
		}

		@Test
		@DisplayName("잘못된 SpEL 조건으로 예외 발생")
		void invalidSpelCondition() throws Exception {
			// given
			LocalDateTime now = LocalDateTime.now();
			Passport passport =
					new Passport(123L, "test@eeos.com", "테스터", List.of("USER"), now, now.plusHours(1));
			String encodedPassport = createEncodedPassport(passport);

			PassportAuth annotation = createPassportAuthWithCondition("invalid.spel.expression");

			when(methodParameter.getParameterAnnotation(PassportAuth.class)).thenReturn(annotation);
			when(webRequest.getNativeRequest(HttpServletRequest.class)).thenReturn(httpRequest);
			when(httpRequest.getHeader("X-User-Passport")).thenReturn(encodedPassport);
			when(httpRequest.getAttribute(any())).thenReturn(null);
			when(httpRequest.getParameterMap()).thenReturn(Map.of());

			// when & then
			assertThatThrownBy(() -> resolver.resolveArgument(methodParameter, null, webRequest, null))
					.isInstanceOf(PassportException.class)
					.hasMessageContaining("Invalid condition expression");
		}

		private String createEncodedPassport(Passport passport) throws Exception {
			String json = objectMapper.writeValueAsString(passport);
			return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
		}
	}
}
