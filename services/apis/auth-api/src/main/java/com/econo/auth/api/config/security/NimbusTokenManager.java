package com.econo.auth.api.config.security;

import com.econo.auth.login.application.domain.DecodedToken;
import com.econo.auth.login.application.domain.TokenModel;
import com.econo.auth.login.application.repository.TokenDecoder;
import com.econo.auth.login.application.repository.TokenEncoder;
import com.econo.auth.login.exception.InvalidTokenException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

/**
 * {@link TokenEncoder}와 {@link TokenDecoder} 두 출력 포트를 모두 구현하는 단일 Nimbus/Spring Security OAuth2 어댑터
 *
 * <p>lib과 oauth2 사이의 인코딩·디코딩 격리 경계를 하나의 빈으로 제공한다. {@link com.econo.auth.api.config.RsaKeyConfig}가
 * 제공하는 {@link JwtEncoder}, {@link JwtDecoder} 빈을 주입받아 RS256 서명 JWT를 발급·검증한다. Spring이 {@code
 * LoginTokenService}의 {@code TokenEncoder}·{@code TokenDecoder} 두 파라미터를 이 단일 빈으로 해소한다.
 */
@Component
@RequiredArgsConstructor
public class NimbusTokenManager implements TokenEncoder, TokenDecoder {

	private final JwtEncoder jwtEncoder;
	private final JwtDecoder jwtDecoder;

	/**
	 * TokenModel을 RS256 서명 JWT 문자열로 인코딩한다
	 *
	 * @param model AT 또는 RT 인코딩 명세
	 * @return 서명된 JWT 문자열
	 */
	@Override
	public String encode(TokenModel model) {
		JwtClaimsSet claims =
				JwtClaimsSet.builder()
						.issuer(model.issuer())
						.subject(model.subject())
						.issuedAt(model.issuedAt())
						.expiresAt(model.expiresAt())
						.claims(c -> c.putAll(model.claims()))
						.build();
		return jwtEncoder
				.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
				.getTokenValue();
	}

	/**
	 * JWT 문자열을 DecodedToken으로 디코딩한다
	 *
	 * @param token JWT 문자열
	 * @return 디코딩된 토큰 (subject, claims)
	 * @throws InvalidTokenException {@link JwtException} 발생 시 래핑
	 */
	@Override
	public DecodedToken decode(String token) throws InvalidTokenException {
		try {
			Jwt jwt = jwtDecoder.decode(token);
			return new DecodedToken(jwt.getSubject(), jwt.getClaims());
		} catch (JwtException e) {
			throw new InvalidTokenException("Token decode failed: " + e.getMessage(), e);
		}
	}
}
