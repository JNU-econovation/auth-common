package com.econo.auth.login.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.econo.auth.login.application.domain.DecodedToken;
import com.econo.auth.login.application.domain.TokenModel;
import com.econo.auth.login.application.repository.TokenDecoder;
import com.econo.auth.login.application.repository.TokenEncoder;
import com.econo.auth.login.application.usecase.LoginTokenUseCase.TokenPair;
import com.econo.auth.login.exception.InvalidTokenException;
import com.econo.auth.login.exception.WrongTokenTypeException;
import com.econo.auth.member.application.domain.Member;
import com.econo.auth.member.application.domain.MemberStatus;
import com.econo.auth.member.application.repository.MemberRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** LoginTokenService 단위 테스트 */
@DisplayName("LoginTokenService — 토큰 발급·검증·재발급 단위 테스트")
@ExtendWith(MockitoExtension.class)
class LoginTokenServiceTest {

	@Mock private TokenEncoder encoder;
	@Mock private TokenDecoder decoder;
	@Mock private MemberRepository memberRepository;

	private LoginTokenService service;

	@BeforeEach
	void setUp() {
		service =
				new LoginTokenService(
						encoder, decoder, memberRepository, "http://auth.test", 3600L, 2592000L);
	}

	private Member member(String role) {
		return Member.restore(
				1L, "홍길동", "honggildong", "hash", 30, MemberStatus.AM, role, LocalDateTime.now());
	}

	// ──────────────────────────────────────────────────────────
	// AT 발급 — 클레임 검증
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("AT 발급 — roles 클레임 검증")
	class IssueAtRolesClaimTest {

		@Test
		@DisplayName("USER 역할 회원 → AT TokenModel에 roles=[USER] 클레임 포함")
		void issue_userRole_atContainsUserRole() {
			// given
			given(encoder.encode(any())).willReturn("at-token", "rt-token");

			ArgumentCaptor<TokenModel> captor = ArgumentCaptor.forClass(TokenModel.class);

			// when
			service.issue(member("USER"));

			// then
			verify(encoder, times(2)).encode(captor.capture());
			TokenModel atModel = captor.getAllValues().get(0);
			@SuppressWarnings("unchecked")
			List<String> roles = (List<String>) atModel.claims().get("roles");
			assertThat(roles).containsExactly("USER");
		}

		@Test
		@DisplayName("ADMIN 역할 회원 → AT TokenModel에 roles=[ADMIN] 클레임 포함")
		void issue_adminRole_atContainsAdminRole() {
			// given
			given(encoder.encode(any())).willReturn("at-token", "rt-token");

			ArgumentCaptor<TokenModel> captor = ArgumentCaptor.forClass(TokenModel.class);

			// when
			service.issue(member("ADMIN"));

			// then
			verify(encoder, times(2)).encode(captor.capture());
			TokenModel atModel = captor.getAllValues().get(0);
			@SuppressWarnings("unchecked")
			List<String> roles = (List<String>) atModel.claims().get("roles");
			assertThat(roles).containsExactly("ADMIN");
		}

		@Test
		@DisplayName("SUPER_ADMIN 역할 회원 → AT TokenModel에 roles=[SUPER_ADMIN] 클레임 포함")
		void issue_superAdminRole_atContainsSuperAdminRole() {
			// given
			given(encoder.encode(any())).willReturn("at-token", "rt-token");

			ArgumentCaptor<TokenModel> captor = ArgumentCaptor.forClass(TokenModel.class);

			// when
			service.issue(member("SUPER_ADMIN"));

			// then
			verify(encoder, times(2)).encode(captor.capture());
			TokenModel atModel = captor.getAllValues().get(0);
			@SuppressWarnings("unchecked")
			List<String> roles = (List<String>) atModel.claims().get("roles");
			assertThat(roles).containsExactly("SUPER_ADMIN");
		}

		@Test
		@DisplayName("AT TokenModel에 memberId, loginId, name, generation, status 클레임 포함")
		void issue_atContainsPassportClaims() {
			// given
			given(encoder.encode(any())).willReturn("at-token", "rt-token");

			ArgumentCaptor<TokenModel> captor = ArgumentCaptor.forClass(TokenModel.class);

			// when
			service.issue(member("USER"));

			// then
			verify(encoder, times(2)).encode(captor.capture());
			TokenModel atModel = captor.getAllValues().get(0);
			assertThat(atModel.claims().get("memberId")).isNotNull();
			assertThat(atModel.claims().get("loginId")).isEqualTo("honggildong");
			assertThat(atModel.claims().get("name")).isEqualTo("홍길동");
			assertThat(atModel.claims().get("generation")).isEqualTo(30);
			assertThat(atModel.claims().get("status")).isEqualTo("AM");
		}

		@Test
		@DisplayName("RT TokenModel에는 roles 클레임 없음 (token_type=refresh만)")
		void issue_rtDoesNotContainRoles() {
			// given
			given(encoder.encode(any())).willReturn("at-token", "rt-token");

			ArgumentCaptor<TokenModel> captor = ArgumentCaptor.forClass(TokenModel.class);

			// when
			service.issue(member("ADMIN"));

			// then
			verify(encoder, times(2)).encode(captor.capture());
			TokenModel rtModel = captor.getAllValues().get(1);
			assertThat(rtModel.claims().get("roles")).isNull();
			assertThat(rtModel.claims().get("token_type")).isEqualTo("refresh");
		}

		@Test
		@DisplayName("AT TokenModel에 token_type=access 클레임 포함")
		void issue_atContainsAccessTokenType() {
			// given
			given(encoder.encode(any())).willReturn("at-token", "rt-token");

			ArgumentCaptor<TokenModel> captor = ArgumentCaptor.forClass(TokenModel.class);

			// when
			service.issue(member("USER"));

			// then
			verify(encoder, times(2)).encode(captor.capture());
			TokenModel atModel = captor.getAllValues().get(0);
			assertThat(atModel.claims().get("token_type")).isEqualTo("access");
		}
	}

	// ──────────────────────────────────────────────────────────
	// verifyRefreshTokenAndGetMemberId 검증
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("verifyRefreshTokenAndGetMemberId — RT 검증")
	class VerifyRefreshTokenTest {

		@Test
		@DisplayName("유효한 RT → memberId 반환")
		void validRefreshToken_returnsMemberId() {
			// given
			DecodedToken decoded = new DecodedToken("1", Map.of("token_type", "refresh"));
			given(decoder.decode("valid-rt")).willReturn(decoded);

			// when
			Long memberId = service.verifyRefreshTokenAndGetMemberId("valid-rt");

			// then
			assertThat(memberId).isEqualTo(1L);
		}

		@Test
		@DisplayName("token_type=access인 AT를 RT로 사용 → WrongTokenTypeException")
		void accessTokenAsRefreshToken_throwsWrongTokenTypeException() {
			// given
			DecodedToken decoded = new DecodedToken("1", Map.of("token_type", "access"));
			given(decoder.decode("access-token")).willReturn(decoded);

			// when / then
			assertThatThrownBy(() -> service.verifyRefreshTokenAndGetMemberId("access-token"))
					.isInstanceOf(WrongTokenTypeException.class);
		}

		@Test
		@DisplayName("decoder.decode() 실패 → InvalidTokenException 전파")
		void decodeFailure_propagatesInvalidTokenException() {
			// given
			given(decoder.decode("bad-token"))
					.willThrow(new InvalidTokenException("Token decode failed"));

			// when / then
			assertThatThrownBy(() -> service.verifyRefreshTokenAndGetMemberId("bad-token"))
					.isInstanceOf(InvalidTokenException.class);
		}
	}

	// ──────────────────────────────────────────────────────────
	// reissue 검증
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("reissue — AT+RT 재발급")
	class ReissueTest {

		@Test
		@DisplayName("memberId로 회원 조회 후 새 TokenPair 반환")
		void reissue_returnNewTokenPair() {
			// given
			Member m = member("USER");
			given(memberRepository.findById(1L)).willReturn(Optional.of(m));
			given(encoder.encode(any())).willReturn("new-at", "new-rt");

			// when
			TokenPair pair = service.reissue(1L);

			// then
			assertThat(pair.accessToken()).isEqualTo("new-at");
			assertThat(pair.refreshToken()).isEqualTo("new-rt");
		}
	}
}
