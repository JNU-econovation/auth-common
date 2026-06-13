package com.econo.auth.api.application.usecase;

import com.econo.auth.member.application.domain.Member;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * 로그인 토큰 발급 유스케이스 입력 포트 인터페이스 (엄격 DIP)
 *
 * <p>{@code ReissueController}, {@code JsonLoginAuthenticationFilter}(config/security), {@code
 * SecurityConfig}(config/security)가 {@code LoginTokenService} 구현체를 직접 주입하는 것을 막는 seam.
 */
public interface LoginTokenUseCase {

	/**
	 * AT + RT 발급 결과
	 *
	 * @param accessToken 액세스 토큰
	 * @param accessExpiredAt 액세스 토큰 만료 시각 (epoch millis)
	 * @param refreshToken 리프레시 토큰
	 */
	record TokenPair(String accessToken, long accessExpiredAt, String refreshToken) {}

	/**
	 * Member 객체로 AT + RT 발급 — 로그인 시 사용
	 *
	 * @param member 회원 도메인 객체
	 * @return 발급된 토큰 페어
	 */
	TokenPair issue(Member member);

	/**
	 * memberId로 회원을 조회해 AT + RT 재발급 — 재발급 요청 시 사용
	 *
	 * @param memberId 회원 PK
	 * @return 새 토큰 페어
	 */
	TokenPair reissue(Long memberId);

	/**
	 * RT를 검증하고 회원 ID를 반환한다
	 *
	 * @param jwt 디코딩된 JWT (Refresh Token)
	 * @return 회원 ID
	 */
	Long extractMemberIdFromRt(Jwt jwt);
}
