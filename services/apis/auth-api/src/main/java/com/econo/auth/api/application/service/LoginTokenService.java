package com.econo.auth.api.application.service;

import com.econo.auth.api.application.usecase.LoginTokenUseCase;
import com.econo.auth.member.application.domain.Member;
import com.econo.auth.member.application.repository.MemberRepository;
import com.econo.auth.member.exception.MemberNotFoundException;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * 로그인 시 AT(Access Token) + RT(Refresh Token)를 발급하는 서비스
 *
 * <p>SAS가 등록한 {@link JwtEncoder}를 재사용하여 RS256 서명 JWT를 생성한다. AT에는 Gateway의 {@code
 * BearerToPassportFilter}가 읽는 Passport 클레임이 포함된다.
 */
@Service
public class LoginTokenService implements LoginTokenUseCase {

	private static final String TOKEN_TYPE_CLAIM = "token_type";
	private static final String ACCESS = "access";
	private static final String REFRESH = "refresh";

	private final JwtEncoder jwtEncoder;
	private final JwtDecoder jwtDecoder;
	private final MemberRepository memberRepository;
	private final String issuer;
	private final long atExpirySeconds;
	private final long rtExpirySeconds;

	public LoginTokenService(
			JwtEncoder jwtEncoder,
			JwtDecoder jwtDecoder,
			MemberRepository memberRepository,
			@Value("${AUTH_ISSUER_URI:http://localhost:8081}") String issuer,
			@Value("${auth.token.at-expiry-seconds:3600}") long atExpirySeconds,
			@Value("${auth.token.rt-expiry-seconds:2592000}") long rtExpirySeconds) {
		this.jwtEncoder = jwtEncoder;
		this.jwtDecoder = jwtDecoder;
		this.memberRepository = memberRepository;
		this.issuer = issuer;
		this.atExpirySeconds = atExpirySeconds;
		this.rtExpirySeconds = rtExpirySeconds;
	}

	/** Member 객체로 AT + RT 발급 — 로그인 시 사용 */
	@Override
	public TokenPair issue(Member member) {
		Instant now = Instant.now();
		String at = encodeAt(member, now);
		String rt = encodeRt(member, now);
		return new TokenPair(at, now.plusSeconds(atExpirySeconds).toEpochMilli(), rt);
	}

	/**
	 * memberId로 회원을 조회해 AT + RT 재발급 — 재발급 요청 시 사용.
	 *
	 * <p>RT 서명 검증 및 token_type 확인은 {@link
	 * com.econo.auth.api.presentation.controller.ReissueController}에서 먼저 수행하고 memberId만 전달한다.
	 *
	 * @param memberId 회원 PK
	 * @return 새 토큰 페어
	 * @throws MemberNotFoundException 회원이 존재하지 않을 때 (JWT 유효하지만 회원 탈퇴 케이스)
	 */
	@Override
	public TokenPair reissue(Long memberId) {
		Member member =
				memberRepository.findById(memberId).orElseThrow(() -> MemberNotFoundException.of(memberId));
		return issue(member);
	}

	/** RT를 검증하고 회원 ID를 반환한다. 만료 검증은 JwtDecoder가 담당. */
	@Override
	public Long extractMemberIdFromRt(org.springframework.security.oauth2.jwt.Jwt jwt) {
		String tokenType = jwt.getClaimAsString(TOKEN_TYPE_CLAIM);
		if (!REFRESH.equals(tokenType)) {
			throw new IllegalArgumentException("Not a refresh token");
		}
		return Long.valueOf(jwt.getSubject());
	}

	private String encodeAt(Member member, Instant now) {
		JwtClaimsSet claims =
				JwtClaimsSet.builder()
						.issuer(issuer)
						.subject(String.valueOf(member.getId()))
						.issuedAt(now)
						.expiresAt(now.plusSeconds(atExpirySeconds))
						.claim("memberId", member.getId())
						.claim("loginId", member.getLoginId())
						.claim("name", member.getName())
						.claim("generation", member.getGeneration())
						.claim("status", member.getStatus().name())
						.claim("roles", List.of(member.getRole()))
						.claim(TOKEN_TYPE_CLAIM, ACCESS)
						.build();
		return jwtEncoder
				.encode(
						JwtEncoderParameters.from(
								JwsHeader.with(
												org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256)
										.build(),
								claims))
				.getTokenValue();
	}

	private String encodeRt(Member member, Instant now) {
		JwtClaimsSet claims =
				JwtClaimsSet.builder()
						.issuer(issuer)
						.subject(String.valueOf(member.getId()))
						.issuedAt(now)
						.expiresAt(now.plusSeconds(rtExpirySeconds))
						.claim(TOKEN_TYPE_CLAIM, REFRESH)
						.build();
		return jwtEncoder
				.encode(
						JwtEncoderParameters.from(
								JwsHeader.with(
												org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256)
										.build(),
								claims))
				.getTokenValue();
	}
}
