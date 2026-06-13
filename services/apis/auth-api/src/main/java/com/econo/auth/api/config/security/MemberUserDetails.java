package com.econo.auth.api.config.security;

import com.econo.auth.member.application.domain.Member;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Spring Security {@link UserDetails} 확장 — {@link Member} 도메인 래퍼
 *
 * <p>{@link #getMember()}로 원본 도메인 객체에 접근하여 Passport 커스텀 클레임({@code memberId}, {@code loginId},
 * {@code name}, {@code generation}, {@code status}, {@code roles})을 주입한다.
 */
@Getter
public class MemberUserDetails implements UserDetails, Serializable {

	@Serial private static final long serialVersionUID = 1L;

	private final Member member;

	/**
	 * Member 도메인 객체로 MemberUserDetails 생성
	 *
	 * @param member 회원 도메인 객체
	 */
	public MemberUserDetails(Member member) {
		this.member = member;
	}

	/**
	 * 부여된 권한 반환 — 회원의 role을 {@code ROLE_} 접두사 권한으로 변환
	 *
	 * @return 권한 컬렉션 (예: role이 {@code ADMIN}이면 {@code ROLE_ADMIN})
	 */
	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		return List.of(new SimpleGrantedAuthority("ROLE_" + member.getRole()));
	}

	/**
	 * BCrypt 해시된 비밀번호 반환
	 *
	 * @return 해시된 비밀번호
	 */
	@Override
	public String getPassword() {
		return member.getHashedPassword();
	}

	/**
	 * 사용자명(loginId) 반환
	 *
	 * @return 로그인 아이디
	 */
	@Override
	public String getUsername() {
		return member.getLoginId();
	}

	/**
	 * 계정 미만료 여부
	 *
	 * @return 항상 {@code true}
	 */
	@Override
	public boolean isAccountNonExpired() {
		return true;
	}

	/**
	 * 계정 잠금 해제 여부
	 *
	 * @return 항상 {@code true}
	 */
	@Override
	public boolean isAccountNonLocked() {
		return true;
	}

	/**
	 * 자격증명 미만료 여부
	 *
	 * @return 항상 {@code true}
	 */
	@Override
	public boolean isCredentialsNonExpired() {
		return true;
	}

	/**
	 * 계정 활성화 여부
	 *
	 * @return 항상 {@code true}
	 */
	@Override
	public boolean isEnabled() {
		return true;
	}
}
