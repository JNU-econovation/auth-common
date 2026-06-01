package com.econo.auth.core.member.application.usecase;

import com.econo.auth.core.member.application.port.in.SignupUseCase;
import com.econo.auth.core.member.application.port.out.MemberRepository;
import com.econo.auth.core.member.application.port.out.PasswordHasher;
import com.econo.auth.core.member.domain.Member;
import com.econo.auth.core.member.exception.InvalidPasswordPolicyException;
import com.econo.auth.core.member.exception.MemberAlreadyExistsException;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;

/** 회원 가입 유스케이스 구현체 */
@RequiredArgsConstructor
public class SignupService implements SignupUseCase {

	private static final Pattern LOGIN_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9\\-_.]{3,19}$");
	private static final Pattern UPPERCASE_PATTERN = Pattern.compile(".*[A-Z].*");
	private static final Pattern LOWERCASE_PATTERN = Pattern.compile(".*[a-z].*");
	private static final Pattern DIGIT_PATTERN = Pattern.compile(".*[0-9].*");
	private static final Pattern SPECIAL_CHAR_PATTERN =
			Pattern.compile(".*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?].*");

	private final MemberRepository memberRepository;
	private final PasswordHasher passwordHasher;

	@Override
	public void signup(SignupCommand command) {
		validateLoginId(command.loginId());
		validateName(command.name());
		validateGeneration(command.generation());
		validatePasswordPolicy(command.password());

		if (memberRepository.existsByLoginId(command.loginId())) {
			throw MemberAlreadyExistsException.of(command.loginId());
		}

		String hashedPassword = passwordHasher.hash(command.password());
		Member member =
				Member.create(
						command.name(),
						command.loginId(),
						hashedPassword,
						command.generation(),
						command.status());
		memberRepository.save(member);
	}

	/** loginId 형식 검증 */
	private void validateLoginId(String loginId) {
		if (loginId == null || !LOGIN_ID_PATTERN.matcher(loginId).matches()) {
			throw new IllegalArgumentException("loginId는 3~19자 영숫자·'-'·'_'·'.'만 사용할 수 있습니다: " + loginId);
		}
	}

	/** name 길이 검증 */
	private void validateName(String name) {
		if (name == null || name.isEmpty() || name.length() > 50) {
			throw new IllegalArgumentException("이름은 1~50자여야 합니다");
		}
	}

	/** generation 범위 검증 */
	private void validateGeneration(Integer generation) {
		if (generation == null || generation < 1 || generation > 99) {
			throw new IllegalArgumentException("기수는 1~99 사이여야 합니다: " + generation);
		}
	}

	/** 비밀번호 정책 검증 */
	private void validatePasswordPolicy(String password) {
		if (password == null || password.length() < 8 || password.length() > 19) {
			throw InvalidPasswordPolicyException.of("비밀번호는 8~19자여야 합니다");
		}
		if (!UPPERCASE_PATTERN.matcher(password).matches()) {
			throw InvalidPasswordPolicyException.of("대문자 누락");
		}
		if (!LOWERCASE_PATTERN.matcher(password).matches()) {
			throw InvalidPasswordPolicyException.of("소문자 누락");
		}
		if (!DIGIT_PATTERN.matcher(password).matches()) {
			throw InvalidPasswordPolicyException.of("숫자 누락");
		}
		if (!SPECIAL_CHAR_PATTERN.matcher(password).matches()) {
			throw InvalidPasswordPolicyException.of("특수기호 누락");
		}
	}
}
