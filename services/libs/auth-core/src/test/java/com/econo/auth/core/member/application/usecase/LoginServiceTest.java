package com.econo.auth.core.member.application.usecase;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import com.econo.auth.core.member.application.port.in.LoginUseCase.LoginCommand;
import com.econo.auth.core.member.application.port.in.LoginUseCase.LoginResult;
import com.econo.auth.core.member.application.port.out.MemberRepository;
import com.econo.auth.core.member.application.port.out.PasswordHasher;
import com.econo.auth.core.member.application.port.out.TokenIssuer;
import com.econo.auth.core.member.domain.Member;
import com.econo.auth.core.member.domain.MemberStatus;
import com.econo.auth.core.member.exception.InvalidCredentialsException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LoginServiceTest {

	@Mock private MemberRepository memberRepository;

	@Mock private PasswordHasher passwordHasher;

	@Mock private TokenIssuer tokenIssuer;

	private LoginService loginService;

	@BeforeEach
	void setUp() {
		loginService = new LoginService(memberRepository, passwordHasher, tokenIssuer);
	}

	/** 테스트용 Member 픽스처 생성 */
	private Member createMember(String loginId, String hashedPassword) {
		return Member.create("홍길동", loginId, hashedPassword, 32, MemberStatus.AM);
	}

	@Nested
	@DisplayName("정상 로그인 테스트")
	class NormalLoginTest {

		@Test
		@DisplayName("유효한 loginId와 비밀번호로 로그인 성공 시 JWT 토큰 반환")
		void loginSuccess() {
			// given
			String loginId = "honggildong";
			String rawPassword = "Econo1234!";
			String hashedPassword = "$2a$12$hashedPassword";
			Member member = createMember(loginId, hashedPassword);

			given(memberRepository.findByLoginId(loginId)).willReturn(Optional.of(member));
			given(passwordHasher.matches(rawPassword, hashedPassword)).willReturn(true);
			given(tokenIssuer.issue(member)).willReturn("eyJhbGciOiJIUzI1NiJ9.jwtToken");

			LoginCommand command = new LoginCommand(loginId, rawPassword);

			// when
			LoginResult result = loginService.login(command);

			// then
			assertThat(result).isNotNull();
			assertThat(result.jwtToken()).isNotBlank();
		}
	}

	@Nested
	@DisplayName("loginId 미존재 테스트")
	class LoginIdNotFoundTest {

		@Test
		@DisplayName("존재하지 않는 loginId로 로그인 시 InvalidCredentialsException 발생")
		void loginWithNotExistingLoginId() {
			// given
			String loginId = "nonexistent";
			given(memberRepository.findByLoginId(loginId)).willReturn(Optional.empty());

			LoginCommand command = new LoginCommand(loginId, "Econo1234!");

			// when & then
			assertThatThrownBy(() -> loginService.login(command))
					.isInstanceOf(InvalidCredentialsException.class);
		}
	}

	@Nested
	@DisplayName("비밀번호 불일치 테스트")
	class PasswordMismatchTest {

		@Test
		@DisplayName("비밀번호 불일치 시 InvalidCredentialsException 발생")
		void loginWithWrongPassword() {
			// given
			String loginId = "honggildong";
			String hashedPassword = "$2a$12$hashedPassword";
			Member member = createMember(loginId, hashedPassword);

			given(memberRepository.findByLoginId(loginId)).willReturn(Optional.of(member));
			given(passwordHasher.matches("wrongPassword!", hashedPassword)).willReturn(false);

			LoginCommand command = new LoginCommand(loginId, "wrongPassword!");

			// when & then
			assertThatThrownBy(() -> loginService.login(command))
					.isInstanceOf(InvalidCredentialsException.class);
		}
	}

	@Nested
	@DisplayName("사용자 열거 방지 테스트")
	class UserEnumerationPreventionTest {

		@Test
		@DisplayName("loginId 미존재와 비밀번호 불일치는 동일한 예외 타입을 반환한다")
		void sameExceptionTypeForNotFoundAndWrongPassword() {
			// given
			String notExistingLoginId = "nonexistent";
			String existingLoginId = "honggildong";
			String hashedPassword = "$2a$12$hashedPassword";
			Member member = createMember(existingLoginId, hashedPassword);

			given(memberRepository.findByLoginId(notExistingLoginId)).willReturn(Optional.empty());
			given(memberRepository.findByLoginId(existingLoginId)).willReturn(Optional.of(member));
			given(passwordHasher.matches("wrongPassword!", hashedPassword)).willReturn(false);

			// when
			LoginCommand notFoundCommand = new LoginCommand(notExistingLoginId, "Econo1234!");
			LoginCommand wrongPasswordCommand = new LoginCommand(existingLoginId, "wrongPassword!");

			Throwable notFoundException = catchThrowable(() -> loginService.login(notFoundCommand));
			Throwable wrongPasswordException =
					catchThrowable(() -> loginService.login(wrongPasswordCommand));

			// then
			assertThat(notFoundException).isInstanceOf(InvalidCredentialsException.class);
			assertThat(wrongPasswordException).isInstanceOf(InvalidCredentialsException.class);
			assertThat(notFoundException.getMessage()).isEqualTo(wrongPasswordException.getMessage());
		}
	}
}
