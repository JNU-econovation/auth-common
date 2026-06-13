package com.econo.auth.api.config.security;

import com.econo.auth.member.application.usecase.MemberQueryUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Spring Security {@link UserDetailsService} 구현 — loginId로 회원 로드
 *
 * <p>{@link MemberQueryUseCase#findByLoginId(String)}로 회원을 조회하고 {@link MemberUserDetails}로 래핑하여
 * 반환한다.
 */
@Service
@RequiredArgsConstructor
public class MemberUserDetailsService implements UserDetailsService {

	private final MemberQueryUseCase memberQueryUseCase;

	/**
	 * loginId로 UserDetails 로드
	 *
	 * <p>비밀번호 검증은 {@link org.springframework.security.authentication.DaoAuthenticationProvider}가
	 * 내부적으로 {@link org.springframework.security.crypto.password.PasswordEncoder}를 통해 수행한다.
	 *
	 * @param loginId 로그인 아이디
	 * @return {@link MemberUserDetails} 인스턴스
	 * @throws UsernameNotFoundException 해당 loginId가 존재하지 않을 경우
	 */
	@Override
	public UserDetails loadUserByUsername(String loginId) throws UsernameNotFoundException {
		return memberQueryUseCase
				.findByLoginId(loginId)
				.map(MemberUserDetails::new)
				.orElseThrow(
						() -> new UsernameNotFoundException("Member not found with loginId: " + loginId));
	}
}
