package com.econo.auth.core.member.application.usecase;

import com.econo.auth.core.member.application.port.in.LoginUseCase;
import com.econo.auth.core.member.application.port.out.MemberRepository;
import com.econo.auth.core.member.application.port.out.PasswordHasher;
import com.econo.auth.core.member.application.port.out.TokenIssuer;
import com.econo.auth.core.member.domain.Member;
import com.econo.auth.core.member.exception.InvalidCredentialsException;
import lombok.RequiredArgsConstructor;

/** 로그인 유스케이스 구현체 */
@RequiredArgsConstructor
public class LoginService implements LoginUseCase {

	private final MemberRepository memberRepository;
	private final PasswordHasher passwordHasher;
	private final TokenIssuer tokenIssuer;

	@Override
	public LoginResult login(LoginCommand command) {
		Member member =
				memberRepository
						.findByLoginId(command.loginId())
						.orElseThrow(InvalidCredentialsException::of);

		if (!passwordHasher.matches(command.password(), member.getHashedPassword())) {
			throw InvalidCredentialsException.of();
		}

		String jwtToken = tokenIssuer.issue(member);
		return new LoginResult(jwtToken);
	}
}
