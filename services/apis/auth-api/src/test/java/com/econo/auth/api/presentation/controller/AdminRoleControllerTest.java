package com.econo.auth.api.presentation.controller;

import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.econo.auth.api.config.security.SecurityConfig;
import com.econo.auth.member.application.domain.Member;
import com.econo.auth.member.application.domain.MemberStatus;
import com.econo.auth.member.application.usecase.MemberQueryUseCase;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** AdminRoleController 웹 레이어 테스트 — CLI 전용 내부 역할 부여 API */
@WebMvcTest(AdminRoleController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "AUTH_INTERNAL_API_KEY=valid-internal-key")
class AdminRoleControllerTest {

	@Autowired private MockMvc mockMvc;
	@MockBean private MemberQueryUseCase memberQueryUseCase;

	private Member mockMember(Long id) {
		return Member.restore(
				id, "테스트", "test", "hash", 30, MemberStatus.AM, "USER", LocalDateTime.now());
	}

	@Nested
	@DisplayName("PUT /api/v1/internal/members/{memberId}/role — 역할 변경 (CLI 전용)")
	class UpdateRoleTest {

		@Test
		@DisplayName("올바른 API Key로 역할 변경 → 200")
		void updateRole_withValidApiKey_returns200() throws Exception {
			given(memberQueryUseCase.findById(1L)).willReturn(Optional.of(mockMember(1L)));

			mockMvc
					.perform(
							put("/api/v1/internal/members/1/role")
									.header("X-Internal-Api-Key", "valid-internal-key")
									.contentType(MediaType.APPLICATION_JSON)
									.content("{\"role\":\"ADMIN\"}"))
					.andExpect(status().isOk());

			then(memberQueryUseCase).should().updateRole(1L, "ADMIN");
		}

		@Test
		@DisplayName("SUPER_ADMIN 역할도 부여 가능")
		void updateRole_superAdmin_returns200() throws Exception {
			given(memberQueryUseCase.findById(1L)).willReturn(Optional.of(mockMember(1L)));

			mockMvc
					.perform(
							put("/api/v1/internal/members/1/role")
									.header("X-Internal-Api-Key", "valid-internal-key")
									.contentType(MediaType.APPLICATION_JSON)
									.content("{\"role\":\"SUPER_ADMIN\"}"))
					.andExpect(status().isOk());

			then(memberQueryUseCase).should().updateRole(1L, "SUPER_ADMIN");
		}

		@Test
		@DisplayName("API Key 없이 요청 시 401 반환")
		void updateRole_withoutApiKey_returns401() throws Exception {
			mockMvc
					.perform(
							put("/api/v1/internal/members/1/role")
									.contentType(MediaType.APPLICATION_JSON)
									.content("{\"role\":\"ADMIN\"}"))
					.andExpect(status().isUnauthorized());
		}

		@Test
		@DisplayName("잘못된 API Key로 요청 시 401 반환")
		void updateRole_withWrongApiKey_returns401() throws Exception {
			mockMvc
					.perform(
							put("/api/v1/internal/members/1/role")
									.header("X-Internal-Api-Key", "wrong-key")
									.contentType(MediaType.APPLICATION_JSON)
									.content("{\"role\":\"ADMIN\"}"))
					.andExpect(status().isUnauthorized());
		}

		@Test
		@DisplayName("존재하지 않는 회원 → 404")
		void updateRole_memberNotFound_returns404() throws Exception {
			given(memberQueryUseCase.findById(999L)).willReturn(Optional.empty());

			mockMvc
					.perform(
							put("/api/v1/internal/members/999/role")
									.header("X-Internal-Api-Key", "valid-internal-key")
									.contentType(MediaType.APPLICATION_JSON)
									.content("{\"role\":\"ADMIN\"}"))
					.andExpect(status().isNotFound());
		}
	}
}
