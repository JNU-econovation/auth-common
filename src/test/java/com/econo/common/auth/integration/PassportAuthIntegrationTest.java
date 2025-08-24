package com.econo.common.auth.integration;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.econo.common.auth.core.passport.Passport;
import com.econo.common.auth.core.passport.PassportException;
import com.econo.common.auth.web.annotation.PassportAuth;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@SpringBootTest(
		classes = {
			PassportAuthIntegrationTest.TestController.class,
			PassportAuthIntegrationTest.TestConfig.class,
			PassportAuthIntegrationTest.TestExceptionHandler.class
		})
@EnableAutoConfiguration
@AutoConfigureMockMvc
class PassportAuthIntegrationTest {

	@Autowired private MockMvc mockMvc;

	@Autowired private ObjectMapper objectMapper;

	@TestConfiguration
	static class TestConfig {
		@Bean
		@org.springframework.context.annotation.Primary
		public ObjectMapper objectMapper() {
			ObjectMapper mapper = new ObjectMapper();
			mapper.registerModule(new JavaTimeModule());
			return mapper;
		}
	}

	@RestController
	@RequestMapping("/test")
	static class TestController {

		@GetMapping("/basic")
		public ResponseEntity<String> basicAuth(@PassportAuth Passport passport) {
			return ResponseEntity.ok("Hello " + passport.getName() + ", ID: " + passport.getMemberId());
		}

		@GetMapping("/admin")
		public ResponseEntity<String> adminOnly(
				@PassportAuth(requiredRoles = "ADMIN") Passport passport) {
			return ResponseEntity.ok("Admin area accessed by " + passport.getName());
		}

		@GetMapping("/manager-hierarchy")
		public ResponseEntity<String> managerWithHierarchy(
				@PassportAuth(requiredRoles = "MANAGER", includeHigherRoles = true) Passport passport) {
			return ResponseEntity.ok("Manager+ area accessed by " + passport.getName());
		}

		@GetMapping("/users/{userId}")
		public ResponseEntity<String> getUserProfile(
				@PathVariable Long userId,
				@PassportAuth(condition = "#passport.memberId == #userId or #passport.isAdmin()")
						Passport passport) {
			return ResponseEntity.ok("Profile of user " + userId + " accessed by " + passport.getName());
		}

		@GetMapping("/optional")
		public ResponseEntity<String> optionalAuth(@PassportAuth(required = false) Passport passport) {
			if (passport != null) {
				return ResponseEntity.ok("Authenticated user: " + passport.getName());
			} else {
				return ResponseEntity.ok("Anonymous user");
			}
		}

		@GetMapping("/multiple-roles")
		public ResponseEntity<String> multipleRoles(
				@PassportAuth(requiredRoles = {"ADMIN", "MANAGER"}) Passport passport) {
			return ResponseEntity.ok("Multiple roles access by " + passport.getName());
		}

		@GetMapping("/all-roles-required")
		public ResponseEntity<String> allRolesRequired(
				@PassportAuth(
								requiredRoles = {"USER", "ACTIVE"},
								requireAllRoles = true)
						Passport passport) {
			return ResponseEntity.ok("All roles required access by " + passport.getName());
		}
	}

	@ControllerAdvice
	static class TestExceptionHandler {
		@ExceptionHandler(PassportException.class)
		public ResponseEntity<String> handlePassportException(PassportException e) {
			return ResponseEntity.status(e.getHttpStatus()).body(e.getMessage());
		}
	}

	private String createEncodedPassport(Long memberId, String email, String name, List<String> roles)
			throws Exception {
		LocalDateTime now = LocalDateTime.now();
		Passport passport = new Passport(memberId, email, name, roles, now, now.plusHours(1));
		String json = objectMapper.writeValueAsString(passport);
		return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	@DisplayName("기본 인증 테스트")
	void basicAuthTest() throws Exception {
		// given
		String encodedPassport = createEncodedPassport(123L, "test@eeos.com", "테스터", List.of("USER"));

		// when & then
		mockMvc
				.perform(get("/test/basic").header("X-User-Passport", encodedPassport))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Hello 테스터")))
				.andExpect(content().string(containsString("ID: 123")));
	}

	@Test
	@DisplayName("인증 헤더 없이 접근 시 401")
	void noAuthHeaderTest() throws Exception {
		mockMvc.perform(get("/test/basic")).andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("관리자 권한 테스트 - 성공")
	void adminRoleSuccessTest() throws Exception {
		// given
		String encodedPassport = createEncodedPassport(123L, "admin@eeos.com", "관리자", List.of("ADMIN"));

		// when & then
		mockMvc
				.perform(get("/test/admin").header("X-User-Passport", encodedPassport))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Admin area accessed by 관리자")));
	}

	@Test
	@DisplayName("관리자 권한 테스트 - 실패")
	void adminRoleFailureTest() throws Exception {
		// given
		String encodedPassport = createEncodedPassport(123L, "user@eeos.com", "사용자", List.of("USER"));

		// when & then
		mockMvc
				.perform(get("/test/admin").header("X-User-Passport", encodedPassport))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("권한 계층 테스트 - ADMIN이 MANAGER 영역 접근")
	void roleHierarchyTest() throws Exception {
		// given
		String encodedPassport = createEncodedPassport(123L, "admin@eeos.com", "관리자", List.of("ADMIN"));

		// when & then
		mockMvc
				.perform(get("/test/manager-hierarchy").header("X-User-Passport", encodedPassport))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Manager+ area accessed by 관리자")));
	}

	@Test
	@DisplayName("권한 계층 테스트 - USER가 MANAGER 영역 접근 실패")
	void roleHierarchyFailureTest() throws Exception {
		// given
		String encodedPassport = createEncodedPassport(123L, "user@eeos.com", "사용자", List.of("USER"));

		// when & then
		mockMvc
				.perform(get("/test/manager-hierarchy").header("X-User-Passport", encodedPassport))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("SpEL 조건 테스트 - 자신의 프로필 접근 성공")
	void spelConditionOwnProfileTest() throws Exception {
		// given
		String encodedPassport = createEncodedPassport(123L, "user@eeos.com", "사용자", List.of("USER"));

		// when & then
		mockMvc
				.perform(get("/test/users/123").header("X-User-Passport", encodedPassport))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Profile of user 123 accessed by 사용자")));
	}

	@Test
	@DisplayName("SpEL 조건 테스트 - 다른 사용자 프로필 접근 실패")
	void spelConditionOtherProfileFailureTest() throws Exception {
		// given
		String encodedPassport = createEncodedPassport(123L, "user@eeos.com", "사용자", List.of("USER"));

		// when & then
		mockMvc
				.perform(get("/test/users/456").header("X-User-Passport", encodedPassport))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("SpEL 조건 테스트 - 관리자가 다른 사용자 프로필 접근 성공")
	void spelConditionAdminAccessTest() throws Exception {
		// given
		String encodedPassport = createEncodedPassport(999L, "admin@eeos.com", "관리자", List.of("ADMIN"));

		// when & then
		mockMvc
				.perform(get("/test/users/123").header("X-User-Passport", encodedPassport))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Profile of user 123 accessed by 관리자")));
	}

	@Test
	@DisplayName("선택적 인증 테스트 - 인증된 사용자")
	void optionalAuthenticatedTest() throws Exception {
		// given
		String encodedPassport = createEncodedPassport(123L, "user@eeos.com", "사용자", List.of("USER"));

		// when & then
		mockMvc
				.perform(get("/test/optional").header("X-User-Passport", encodedPassport))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Authenticated user: 사용자")));
	}

	@Test
	@DisplayName("선택적 인증 테스트 - 익명 사용자")
	void optionalAnonymousTest() throws Exception {
		// when & then
		mockMvc
				.perform(get("/test/optional"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Anonymous user")));
	}

	@Test
	@DisplayName("다중 권한 테스트 - OR 조건 성공")
	void multipleRolesOrConditionTest() throws Exception {
		// given
		String encodedPassport =
				createEncodedPassport(123L, "manager@eeos.com", "매니저", List.of("MANAGER"));

		// when & then
		mockMvc
				.perform(get("/test/multiple-roles").header("X-User-Passport", encodedPassport))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Multiple roles access by 매니저")));
	}

	@Test
	@DisplayName("모든 권한 필요 테스트 - AND 조건 성공")
	void allRolesRequiredSuccessTest() throws Exception {
		// given
		String encodedPassport =
				createEncodedPassport(123L, "user@eeos.com", "사용자", List.of("USER", "ACTIVE"));

		// when & then
		mockMvc
				.perform(get("/test/all-roles-required").header("X-User-Passport", encodedPassport))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("All roles required access by 사용자")));
	}

	@Test
	@DisplayName("모든 권한 필요 테스트 - AND 조건 실패")
	void allRolesRequiredFailureTest() throws Exception {
		// given
		String encodedPassport =
				createEncodedPassport(123L, "user@eeos.com", "사용자", List.of("USER")); // ACTIVE 권한 없음

		// when & then
		mockMvc
				.perform(get("/test/all-roles-required").header("X-User-Passport", encodedPassport))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("만료된 토큰 테스트")
	void expiredTokenTest() throws Exception {
		// given
		LocalDateTime now = LocalDateTime.now();
		Passport expiredPassport =
				new Passport(
						123L,
						"user@eeos.com",
						"사용자",
						List.of("USER"),
						now.minusHours(2),
						now.minusHours(1)); // 1시간 전에 만료
		String json = objectMapper.writeValueAsString(expiredPassport);
		String encodedPassport =
				Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));

		// when & then
		mockMvc
				.perform(get("/test/basic").header("X-User-Passport", encodedPassport))
				.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("잘못된 형식의 토큰 테스트")
	void invalidTokenFormatTest() throws Exception {
		// when & then
		mockMvc
				.perform(get("/test/basic").header("X-User-Passport", "invalid-base64-token"))
				.andExpect(status().isBadRequest());
	}
}
