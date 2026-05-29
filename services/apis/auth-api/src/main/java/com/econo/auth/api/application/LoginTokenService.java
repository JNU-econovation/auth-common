package com.econo.auth.api.application;

import com.econo.auth.core.member.domain.Member;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
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
public class LoginTokenService {

	private static final String TOKEN_TYPE_CLAIM = "token_type";
	private static final String ACCESS = "access";
	private static final String REFRESH = "refresh";

	private final JwtEncoder jwtEncoder;
	private final String issuer;
	private final long atExpirySeconds;
	private final long rtExpirySeconds;

	public LoginTokenService(
			JwtEncoder jwtEncoder,
			@Value("${AUTH_ISSUER_URI:http://localhost:8081}") String issuer,
			@Value("${auth.token.at-expiry-seconds:3600}") long atExpirySeconds,
			@Value("${auth.token.rt-expiry-seconds:2592000}") long rtExpirySeconds) {
		this.jwtEncoder = jwtEncoder;
		this.issuer = issuer;
		this.atExpirySeconds = atExpirySeconds;
		this.rtExpirySeconds = rtExpirySeconds;
	}

	public record TokenPair(String accessToken, long accessExpiredAt, String refreshToken) {}

	public TokenPair issue(Member member) {
		Instant now = Instant.now();
		String at = encodeAt(member, now);
		String rt = encodeRt(member, now);
		return new TokenPair(at, now.plusSeconds(atExpirySeconds).toEpochMilli(), rt);
	}

	/** RT를 검증하고 회원 ID를 반환한다. 만료 검증은 JwtDecoder가 담당. */
	public Long extractMemberIdFromRt(org.springframework.security.oauth2.jwt.Jwt jwt) {
		String tokenType = jwt.getClaimAsString(TOKEN_TYPE_CLAIM);
		if (!REFRESH.equals(tokenType)) {
			throw new IllegalArgumentException("Not a refresh token");
		}
		return Long.valueOf(jwt.getSubject());
	}

	public TokenPair reissue(Member member) {
		return issue(member);
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
						.claim("roles", List.of("USER"))
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
