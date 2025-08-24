package com.econo.common.auth.core.passport;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import lombok.Getter;

/**
 * 마이크로서비스 간 통신용 회원 Passport Aggregate Root Gateway에서 JWT 토큰을 파싱하여 생성하고, 각 서비스에서 @PassportAuth
 * 어노테이션으로 주입받아 사용
 */
@Getter
public class Passport {

	@NotNull private final Long memberId;
	private final String email;
	private final String name;

	@NotNull private final List<String> roles;

	@NotNull private final LocalDateTime issuedAt;

	@NotNull private final LocalDateTime expiresAt;

	@JsonCreator
	public Passport(
			@JsonProperty("memberId") Long memberId,
			@JsonProperty("email") String email,
			@JsonProperty("name") String name,
			@JsonProperty("roles") List<String> roles,
			@JsonProperty("issuedAt") LocalDateTime issuedAt,
			@JsonProperty("expiresAt") LocalDateTime expiresAt) {
		this.memberId = memberId;
		this.email = email;
		this.name = name;
		this.roles = roles != null ? List.copyOf(roles) : List.of();
		this.issuedAt = issuedAt;
		this.expiresAt = expiresAt;
	}

	/** 관리자 권한 여부 확인 */
	@JsonIgnore
	public boolean isAdmin() {
		return roles.contains("ADMIN");
	}

	/** 매니저 권한 여부 확인 */
	@JsonIgnore
	public boolean isManager() {
		return roles.contains("MANAGER");
	}

	/** 특정 권한 보유 여부 확인 */
	@JsonIgnore
	public boolean hasRole(String role) {
		return roles.contains(role);
	}

	/** 여러 권한 중 하나라도 보유하는지 확인 */
	@JsonIgnore
	public boolean hasAnyRole(String... roles) {
		for (String role : roles) {
			if (hasRole(role)) {
				return true;
			}
		}
		return false;
	}

	/** 모든 권한을 보유하는지 확인 */
	@JsonIgnore
	public boolean hasAllRoles(String... roles) {
		for (String role : roles) {
			if (!hasRole(role)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * 시간 기반 만료 검증 - 현재 시간 기준으로 만료되었는지 확인
	 *
	 * <p>구조적 유효성과는 무관하게 시간적으로만 만료 여부를 판단합니다.
	 *
	 * @return 현재 시간이 expiresAt을 초과했으면 true, 그렇지 않으면 false
	 * @see #isValid() 구조적 유효성 검증
	 * @see #isActive() 종합적 사용 가능 여부 검증
	 */
	@JsonIgnore
	public boolean isExpired() {
		return expiresAt != null && LocalDateTime.now().isAfter(expiresAt);
	}

	/**
	 * 구조적 유효성 검증 - 필수 데이터가 존재하는지 확인
	 *
	 * <p>만료 여부와는 무관하게 Passport 객체 자체가 올바르게 구성되었는지 검사합니다.
	 *
	 * @return memberId가 존재하면 true, 그렇지 않으면 false
	 * @see #isExpired() 시간 기반 만료 검증
	 * @see #isActive() 종합적 사용 가능 여부 검증
	 */
	@JsonIgnore
	public boolean isValid() {
		return memberId != null;
	}

	/**
	 * 종합적 사용 가능 여부 검증 - 구조적으로 유효하면서 만료되지 않았는지 확인
	 *
	 * <p>실제 비즈니스 로직에서 Passport를 사용할 수 있는지 종합적으로 판단합니다.
	 *
	 * @return 구조적으로 유효하고 만료되지 않았으면 true, 그렇지 않으면 false
	 * @see #isValid() 구조적 유효성 검증
	 * @see #isExpired() 시간 기반 만료 검증
	 */
	@JsonIgnore
	public boolean isActive() {
		return isValid() && !isExpired();
	}

	/** 특정 사용자인지 확인 */
	@JsonIgnore
	public boolean isMember(Long memberId) {
		return Objects.equals(this.memberId, memberId);
	}

	/** 자신 또는 관리자인지 확인 (권한 체크용) */
	@JsonIgnore
	public boolean canAccessMember(Long targetMemberId) {
		return isMember(targetMemberId) || isAdmin();
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		Passport passport = (Passport) o;
		return Objects.equals(memberId, passport.memberId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(memberId);
	}

	@Override
	public String toString() {
		return "Passport{"
				+ "memberId="
				+ memberId
				+ ", name='"
				+ name
				+ '\''
				+ ", roles="
				+ roles
				+ ", isExpired="
				+ isExpired()
				+ '}';
	}
}
