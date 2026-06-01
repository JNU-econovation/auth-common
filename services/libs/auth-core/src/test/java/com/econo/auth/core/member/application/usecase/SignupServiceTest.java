package com.econo.auth.core.member.application.usecase;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import com.econo.auth.core.member.application.port.in.SignupUseCase.SignupCommand;
import com.econo.auth.core.member.application.port.out.MemberRepository;
import com.econo.auth.core.member.application.port.out.PasswordHasher;
import com.econo.auth.core.member.domain.Member;
import com.econo.auth.core.member.domain.MemberStatus;
import com.econo.auth.core.member.exception.InvalidPasswordPolicyException;
import com.econo.auth.core.member.exception.MemberAlreadyExistsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SignupServiceTest {

	@Mock private MemberRepository memberRepository;

	@Mock private PasswordHasher passwordHasher;

	private SignupService signupService;

	@BeforeEach
	void setUp() {
		signupService = new SignupService(memberRepository, passwordHasher);
	}

	@Nested
	@DisplayName("정상 가입 테스트")
	class NormalSignupTest {

		@Test
		@DisplayName("유효한 커맨드로 가입 성공")
		void signupSuccess() {
			// given
			SignupCommand command =
					new SignupCommand("홍길동", "honggildong", "Econo1234!", 32, MemberStatus.AM);
			given(memberRepository.existsByLoginId("honggildong")).willReturn(false);
			given(passwordHasher.hash("Econo1234!")).willReturn("$2a$12$hashedPassword");

			// when & then
			assertThatNoException().isThrownBy(() -> signupService.signup(command));
			then(memberRepository).should().save(any(Member.class));
		}
	}

	@Nested
	@DisplayName("loginId 중복 테스트")
	class LoginIdDuplicateTest {

		@Test
		@DisplayName("이미 존재하는 loginId로 가입 시 MemberAlreadyExistsException 발생")
		void signupWithDuplicateLoginId() {
			// given
			SignupCommand command =
					new SignupCommand("홍길동", "honggildong", "Econo1234!", 32, MemberStatus.AM);
			given(memberRepository.existsByLoginId("honggildong")).willReturn(true);

			// when & then
			assertThatThrownBy(() -> signupService.signup(command))
					.isInstanceOf(MemberAlreadyExistsException.class);
		}
	}

	@Nested
	@DisplayName("loginId 형식 오류 테스트")
	class LoginIdFormatTest {

		@ParameterizedTest
		@DisplayName("3자 미만 loginId는 거부")
		@ValueSource(strings = {"ab", "a"})
		void rejectLoginIdTooShort(String loginId) {
			// given
			SignupCommand command = new SignupCommand("홍길동", loginId, "Econo1234!", 32, MemberStatus.AM);

			// when & then
			assertThatThrownBy(() -> signupService.signup(command))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("20자 이상 loginId는 거부")
		void rejectLoginIdTooLong() {
			// given
			String longLoginId = "a".repeat(20); // 20자 — 상한이 19자이므로 거부 대상
			SignupCommand command =
					new SignupCommand("홍길동", longLoginId, "Econo1234!", 32, MemberStatus.AM);

			// when & then
			assertThatThrownBy(() -> signupService.signup(command))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@ParameterizedTest
		@DisplayName("허용 외 문자(공백, 한글, 특수기호)가 포함된 loginId는 거부")
		@ValueSource(strings = {"user name", "사용자123", "user@id", "user#1"})
		void rejectLoginIdWithInvalidChars(String loginId) {
			// given
			SignupCommand command = new SignupCommand("홍길동", loginId, "Econo1234!", 32, MemberStatus.AM);

			// when & then
			assertThatThrownBy(() -> signupService.signup(command))
					.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Nested
	@DisplayName("name 길이 위반 테스트")
	class NameLengthTest {

		@Test
		@DisplayName("빈 name은 거부")
		void rejectEmptyName() {
			// given
			SignupCommand command =
					new SignupCommand("", "honggildong", "Econo1234!", 32, MemberStatus.AM);

			// when & then
			assertThatThrownBy(() -> signupService.signup(command))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("51자 이상 name은 거부")
		void rejectNameTooLong() {
			// given
			String longName = "가".repeat(51);
			SignupCommand command =
					new SignupCommand(longName, "honggildong", "Econo1234!", 32, MemberStatus.AM);

			// when & then
			assertThatThrownBy(() -> signupService.signup(command))
					.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Nested
	@DisplayName("generation 범위 외 테스트")
	class GenerationRangeTest {

		@ParameterizedTest
		@DisplayName("0 이하 generation은 거부")
		@ValueSource(ints = {0, -1, -100})
		void rejectGenerationZeroOrNegative(int generation) {
			// given
			SignupCommand command =
					new SignupCommand("홍길동", "honggildong", "Econo1234!", generation, MemberStatus.AM);

			// when & then
			assertThatThrownBy(() -> signupService.signup(command))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("100 이상 generation은 거부")
		void rejectGenerationHundredOrMore() {
			// given
			SignupCommand command =
					new SignupCommand("홍길동", "honggildong", "Econo1234!", 100, MemberStatus.AM);

			// when & then
			assertThatThrownBy(() -> signupService.signup(command))
					.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Nested
	@DisplayName("비밀번호 정책 위반 테스트")
	class PasswordPolicyTest {

		@Test
		@DisplayName("8자 미만 비밀번호는 거부")
		void rejectPasswordTooShort() {
			// given
			SignupCommand command = new SignupCommand("홍길동", "honggildong", "Ec1!", 32, MemberStatus.AM);

			// when & then
			assertThatThrownBy(() -> signupService.signup(command))
					.isInstanceOf(InvalidPasswordPolicyException.class);
		}

		@Test
		@DisplayName("20자 이상 비밀번호는 거부")
		void rejectPasswordTooLong() {
			// given
			String longPassword = "Econo1234!Econo1234!!"; // 21자
			SignupCommand command =
					new SignupCommand("홍길동", "honggildong", longPassword, 32, MemberStatus.AM);

			// when & then
			assertThatThrownBy(() -> signupService.signup(command))
					.isInstanceOf(InvalidPasswordPolicyException.class);
		}

		@Test
		@DisplayName("대문자가 없는 비밀번호는 거부")
		void rejectPasswordWithoutUppercase() {
			// given
			SignupCommand command =
					new SignupCommand("홍길동", "honggildong", "econo1234!", 32, MemberStatus.AM);

			// when & then
			assertThatThrownBy(() -> signupService.signup(command))
					.isInstanceOf(InvalidPasswordPolicyException.class);
		}

		@Test
		@DisplayName("소문자가 없는 비밀번호는 거부")
		void rejectPasswordWithoutLowercase() {
			// given
			SignupCommand command =
					new SignupCommand("홍길동", "honggildong", "ECONO1234!", 32, MemberStatus.AM);

			// when & then
			assertThatThrownBy(() -> signupService.signup(command))
					.isInstanceOf(InvalidPasswordPolicyException.class);
		}

		@Test
		@DisplayName("숫자가 없는 비밀번호는 거부")
		void rejectPasswordWithoutDigit() {
			// given
			SignupCommand command =
					new SignupCommand("홍길동", "honggildong", "EconoABCD!", 32, MemberStatus.AM);

			// when & then
			assertThatThrownBy(() -> signupService.signup(command))
					.isInstanceOf(InvalidPasswordPolicyException.class);
		}

		@Test
		@DisplayName("특수기호가 없는 비밀번호는 거부")
		void rejectPasswordWithoutSpecialChar() {
			// given
			SignupCommand command =
					new SignupCommand("홍길동", "honggildong", "Econo1234", 32, MemberStatus.AM);

			// when & then
			assertThatThrownBy(() -> signupService.signup(command))
					.isInstanceOf(InvalidPasswordPolicyException.class);
		}
	}
}
