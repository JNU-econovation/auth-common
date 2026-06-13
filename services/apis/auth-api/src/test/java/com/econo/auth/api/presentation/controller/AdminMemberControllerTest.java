package com.econo.auth.api.presentation.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.econo.auth.api.config.security.SecurityConfig;
import com.econo.auth.member.application.domain.Member;
import com.econo.auth.member.application.domain.MemberStatus;
import com.econo.auth.member.application.usecase.MemberQueryUseCase;
import com.econo.common.auth.config.AuthAutoConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** AdminMemberController 웹 레이어 테스트 */
@WebMvcTest(AdminMemberController.class)
@Import({SecurityConfig.class, AuthAutoConfiguration.class})
class AdminMemberControllerTest {

	@Autowired private MockMvc mockMvc;
	@MockBean private MemberQueryUseCase memberQueryUseCase;

	private static final String SUPER_ADMIN_PASSPORT =
			Base64.getEncoder()
					.encodeToString(
							"{\"memberId\":1,\"roles\":[\"SUPER_ADMIN\"],\"issuedAt\":\"2026-01-01T00:00:00\",\"expiresAt\":\"2099-01-01T00:00:00\"}"
									.getBytes(StandardCharsets.UTF_8));

	private static final String ADMIN_PASSPORT =
			Base64.getEncoder()
					.encodeToString(
							"{\"memberId\":2,\"roles\":[\"ADMIN\"],\"issuedAt\":\"2026-01-01T00:00:00\",\"expiresAt\":\"2099-01-01T00:00:00\"}"
									.getBytes(StandardCharsets.UTF_8));

	private static final String USER_PASSPORT =
			Base64.getEncoder()
					.encodeToString(
							"{\"memberId\":3,\"roles\":[\"USER\"],\"issuedAt\":\"2026-01-01T00:00:00\",\"expiresAt\":\"2099-01-01T00:00:00\"}"
									.getBytes(StandardCharsets.UTF_8));

	private Member mockMember(Long id, String role) {
		return Member.restore(
				id, "테스트", "test" + id, "hash", 30, MemberStatus.AM, role, LocalDateTime.now());
	}

	// ──────────────────────────────────────────────────────────
	// GET /api/v1/admin/members
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("GET /api/v1/admin/members — 회원 목록 조회")
	class ListMembersTest {

		@Test
		@DisplayName("ADMIN 역할로 조회 시 200 반환")
		void listMembers_withAdmin_returns200() throws Exception {
			given(memberQueryUseCase.findPaged(0, 20, null)).willReturn(List.of(mockMember(10L, "USER")));
			given(memberQueryUseCase.count(null)).willReturn(1L);

			mockMvc
					.perform(get("/api/v1/admin/members").header("X-User-Passport", ADMIN_PASSPORT))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.content").isArray())
					.andExpect(jsonPath("$.totalElements").value(1));
		}

		@Test
		@DisplayName("SUPER_ADMIN 역할로 조회 시 200 반환")
		void listMembers_withSuperAdmin_returns200() throws Exception {
			given(memberQueryUseCase.findPaged(0, 20, null)).willReturn(List.of());
			given(memberQueryUseCase.count(null)).willReturn(0L);

			mockMvc
					.perform(get("/api/v1/admin/members").header("X-User-Passport", SUPER_ADMIN_PASSPORT))
					.andExpect(status().isOk());
		}

		@Test
		@DisplayName("role 필터 파라미터 전달 시 해당 역할만 조회")
		void listMembers_withRoleFilter_returns200() throws Exception {
			given(memberQueryUseCase.findPaged(0, 20, "ADMIN"))
					.willReturn(List.of(mockMember(10L, "ADMIN")));
			given(memberQueryUseCase.count("ADMIN")).willReturn(1L);

			mockMvc
					.perform(
							get("/api/v1/admin/members")
									.param("role", "ADMIN")
									.header("X-User-Passport", SUPER_ADMIN_PASSPORT))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.content[0].role").value("ADMIN"));
		}

		@Test
		@DisplayName("USER 역할로 요청 시 403 반환")
		void listMembers_withUser_returns403() throws Exception {
			mockMvc
					.perform(get("/api/v1/admin/members").header("X-User-Passport", USER_PASSPORT))
					.andExpect(status().isForbidden());
		}

		@Test
		@DisplayName("Passport 없이 요청 시 401 반환")
		void listMembers_withoutPassport_returns401() throws Exception {
			mockMvc.perform(get("/api/v1/admin/members")).andExpect(status().isUnauthorized());
		}
	}

	// ──────────────────────────────────────────────────────────
	// PATCH /api/v1/admin/members/{memberId}/role
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("PATCH /api/v1/admin/members/{memberId}/role — 역할 변경")
	class UpdateRoleTest {

		@Test
		@DisplayName("SUPER_ADMIN이 타인의 역할을 ADMIN으로 변경 → 200")
		void updateRole_superAdmin_grantAdmin_returns200() throws Exception {
			given(memberQueryUseCase.findById(10L)).willReturn(Optional.of(mockMember(10L, "USER")));

			mockMvc
					.perform(
							patch("/api/v1/admin/members/10/role")
									.header("X-User-Passport", SUPER_ADMIN_PASSPORT)
									.contentType(MediaType.APPLICATION_JSON)
									.content("{\"role\":\"ADMIN\"}"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.memberId").value(10))
					.andExpect(jsonPath("$.role").value("ADMIN"));
		}

		@Test
		@DisplayName("ADMIN 역할로 역할 변경 시도 → 403 (SUPER_ADMIN 전용)")
		void updateRole_adminRole_returns403() throws Exception {
			mockMvc
					.perform(
							patch("/api/v1/admin/members/10/role")
									.header("X-User-Passport", ADMIN_PASSPORT)
									.contentType(MediaType.APPLICATION_JSON)
									.content("{\"role\":\"ADMIN\"}"))
					.andExpect(status().isForbidden());
		}

		@Test
		@DisplayName("본인 역할 변경 시도 → 403 FORBIDDEN_SELF_ROLE_CHANGE")
		void updateRole_selfChange_returns403() throws Exception {
			// SUPER_ADMIN_PASSPORT의 memberId는 1
			mockMvc
					.perform(
							patch("/api/v1/admin/members/1/role")
									.header("X-User-Passport", SUPER_ADMIN_PASSPORT)
									.contentType(MediaType.APPLICATION_JSON)
									.content("{\"role\":\"USER\"}"))
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.errorCode").value("FORBIDDEN_SELF_ROLE_CHANGE"));
		}

		@Test
		@DisplayName("유효하지 않은 역할 입력 → 400 INVALID_ROLE")
		void updateRole_invalidRole_returns400() throws Exception {
			given(memberQueryUseCase.findById(10L)).willReturn(Optional.of(mockMember(10L, "USER")));

			mockMvc
					.perform(
							patch("/api/v1/admin/members/10/role")
									.header("X-User-Passport", SUPER_ADMIN_PASSPORT)
									.contentType(MediaType.APPLICATION_JSON)
									.content("{\"role\":\"MANAGER\"}"))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errorCode").value("INVALID_ROLE"));
		}

		@Test
		@DisplayName("존재하지 않는 회원 → 404")
		void updateRole_memberNotFound_returns404() throws Exception {
			given(memberQueryUseCase.findById(999L)).willReturn(Optional.empty());

			mockMvc
					.perform(
							patch("/api/v1/admin/members/999/role")
									.header("X-User-Passport", SUPER_ADMIN_PASSPORT)
									.contentType(MediaType.APPLICATION_JSON)
									.content("{\"role\":\"ADMIN\"}"))
					.andExpect(status().isNotFound());
		}

		@Test
		@DisplayName("마지막 SUPER_ADMIN 해제 시도 → 409 LAST_SUPER_ADMIN_CANNOT_BE_DEMOTED")
		void updateRole_lastSuperAdmin_returns409() throws Exception {
			given(memberQueryUseCase.findById(10L))
					.willReturn(Optional.of(mockMember(10L, "SUPER_ADMIN")));
			given(memberQueryUseCase.countByRole("SUPER_ADMIN")).willReturn(1L);

			mockMvc
					.perform(
							patch("/api/v1/admin/members/10/role")
									.header("X-User-Passport", SUPER_ADMIN_PASSPORT)
									.contentType(MediaType.APPLICATION_JSON)
									.content("{\"role\":\"USER\"}"))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.errorCode").value("LAST_SUPER_ADMIN_CANNOT_BE_DEMOTED"));
		}

		@Test
		@DisplayName("SUPER_ADMIN이 여러 명일 때 해제 → 200")
		void updateRole_multipleSuperAdmins_canDemote() throws Exception {
			given(memberQueryUseCase.findById(10L))
					.willReturn(Optional.of(mockMember(10L, "SUPER_ADMIN")));
			given(memberQueryUseCase.countByRole("SUPER_ADMIN")).willReturn(2L);

			mockMvc
					.perform(
							patch("/api/v1/admin/members/10/role")
									.header("X-User-Passport", SUPER_ADMIN_PASSPORT)
									.contentType(MediaType.APPLICATION_JSON)
									.content("{\"role\":\"ADMIN\"}"))
					.andExpect(status().isOk());
		}

		@Test
		@DisplayName("role 값이 소문자여도 처리 → 200 (대소문자 무관)")
		void updateRole_lowercaseRole_returns200() throws Exception {
			given(memberQueryUseCase.findById(10L)).willReturn(Optional.of(mockMember(10L, "USER")));

			mockMvc
					.perform(
							patch("/api/v1/admin/members/10/role")
									.header("X-User-Passport", SUPER_ADMIN_PASSPORT)
									.contentType(MediaType.APPLICATION_JSON)
									.content("{\"role\":\"admin\"}"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.role").value("ADMIN"));
		}
	}
}
