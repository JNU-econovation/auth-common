package com.econo.auth.api.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import com.econo.auth.member.domain.Member;
import com.econo.auth.member.domain.MemberStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

/** LoginTokenService 단위 테스트 */
@ExtendWith(MockitoExtension.class)
class LoginTokenServiceTest {

	@Mock private JwtEncoder jwtEncoder;
	@Mock private JwtDecoder jwtDecoder;
	@Mock private com.econo.auth.member.application.port.out.MemberRepository memberRepository;

	private LoginTokenService service;

	@BeforeEach
	void setUp() {
		service =
				new LoginTokenService(
						jwtEncoder, jwtDecoder, memberRepository, "http://auth.test", 3600L, 2592000L);
	}

	private static Jwt fakeJwt(String tokenValue) {
		return Jwt.withTokenValue(tokenValue).header("alg", "RS256").subject("1").build();
	}

	private Member member(String role) {
		return Member.restore(
				1L, "홍길동", "honggildong", "hash", 30, MemberStatus.AM, role, LocalDateTime.now());
	}

	// ──────────────────────────────────────────────────────────
	// AT 발급 — roles 클레임
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("AT 발급 — roles 클레임 검증")
	class IssueAtRolesClaimTest {

		@Test
		@DisplayName("USER 역할 회원 → AT에 roles=[USER] 클레임 포함")
		void issue_userRole_atContainsUserRole() {
			given(jwtEncoder.encode(any())).willReturn(fakeJwt("at-token"), fakeJwt("rt-token"));

			ArgumentCaptor<JwtEncoderParameters> captor =
					ArgumentCaptor.forClass(JwtEncoderParameters.class);
			service.issue(member("USER"));

			verify(jwtEncoder, times(2)).encode(captor.capture());
			JwtClaimsSet atClaims = captor.getAllValues().get(0).getClaims();
			List<String> roles = atClaims.getClaim("roles");
			assertThat(roles).containsExactly("USER");
		}

		@Test
		@DisplayName("ADMIN 역할 회원 → AT에 roles=[ADMIN] 클레임 포함")
		void issue_adminRole_atContainsAdminRole() {
			given(jwtEncoder.encode(any())).willReturn(fakeJwt("at-token"), fakeJwt("rt-token"));

			ArgumentCaptor<JwtEncoderParameters> captor =
					ArgumentCaptor.forClass(JwtEncoderParameters.class);
			service.issue(member("ADMIN"));

			verify(jwtEncoder, times(2)).encode(captor.capture());
			JwtClaimsSet atClaims = captor.getAllValues().get(0).getClaims();
			List<String> roles = atClaims.getClaim("roles");
			assertThat(roles).containsExactly("ADMIN");
		}

		@Test
		@DisplayName("SUPER_ADMIN 역할 회원 → AT에 roles=[SUPER_ADMIN] 클레임 포함")
		void issue_superAdminRole_atContainsSuperAdminRole() {
			given(jwtEncoder.encode(any())).willReturn(fakeJwt("at-token"), fakeJwt("rt-token"));

			ArgumentCaptor<JwtEncoderParameters> captor =
					ArgumentCaptor.forClass(JwtEncoderParameters.class);
			service.issue(member("SUPER_ADMIN"));

			verify(jwtEncoder, times(2)).encode(captor.capture());
			JwtClaimsSet atClaims = captor.getAllValues().get(0).getClaims();
			List<String> roles = atClaims.getClaim("roles");
			assertThat(roles).containsExactly("SUPER_ADMIN");
		}

		@Test
		@DisplayName("AT에 memberId, loginId, name, generation, status 클레임 포함")
		void issue_atContainsPassportClaims() {
			given(jwtEncoder.encode(any())).willReturn(fakeJwt("at-token"), fakeJwt("rt-token"));

			ArgumentCaptor<JwtEncoderParameters> captor =
					ArgumentCaptor.forClass(JwtEncoderParameters.class);
			service.issue(member("USER"));

			verify(jwtEncoder, times(2)).encode(captor.capture());
			JwtClaimsSet atClaims = captor.getAllValues().get(0).getClaims();
			assertThat((Object) atClaims.getClaim("memberId")).isNotNull();
			assertThat((Object) atClaims.getClaim("loginId")).isEqualTo("honggildong");
			assertThat((Object) atClaims.getClaim("name")).isEqualTo("홍길동");
			assertThat((Object) atClaims.getClaim("generation")).isEqualTo(30);
			assertThat((Object) atClaims.getClaim("status")).isEqualTo("AM");
		}

		@Test
		@DisplayName("RT에는 roles 클레임 없음 (token_type=refresh만)")
		void issue_rtDoesNotContainRoles() {
			given(jwtEncoder.encode(any())).willReturn(fakeJwt("at-token"), fakeJwt("rt-token"));

			ArgumentCaptor<JwtEncoderParameters> captor =
					ArgumentCaptor.forClass(JwtEncoderParameters.class);
			service.issue(member("ADMIN"));

			verify(jwtEncoder, times(2)).encode(captor.capture());
			JwtClaimsSet rtClaims = captor.getAllValues().get(1).getClaims();
			assertThat((Object) rtClaims.getClaim("roles")).isNull();
			assertThat((Object) rtClaims.getClaim("token_type")).isEqualTo("refresh");
		}
	}
}
