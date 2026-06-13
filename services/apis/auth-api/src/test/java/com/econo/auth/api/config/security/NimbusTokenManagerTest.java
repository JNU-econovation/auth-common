package com.econo.auth.api.config.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.econo.auth.login.application.domain.DecodedToken;
import com.econo.auth.login.application.domain.TokenModel;
import com.econo.auth.login.exception.InvalidTokenException;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtException;

/** NimbusTokenManager 단위 테스트 */
@DisplayName("NimbusTokenManager — JWT 인코딩·디코딩 단위 테스트")
@ExtendWith(MockitoExtension.class)
class NimbusTokenManagerTest {

	@Mock private JwtEncoder jwtEncoder;
	@Mock private JwtDecoder jwtDecoder;

	private NimbusTokenManager manager;

	@BeforeEach
	void setUp() {
		manager = new NimbusTokenManager(jwtEncoder, jwtDecoder);
	}

	/** 테스트용 Jwt 객체 생성 헬퍼 */
	private Jwt fakeJwt(String tokenValue, String subject, Map<String, Object> claims) {
		Jwt.Builder builder = Jwt.withTokenValue(tokenValue).header("alg", "RS256").subject(subject);
		claims.forEach(builder::claim);
		return builder.build();
	}

	@Nested
	@DisplayName("encode — TokenModel → JWT 문자열")
	class EncodeTest {

		@Test
		@DisplayName("encode() 호출 시 JwtEncoder.encode()가 반환한 tokenValue를 그대로 반환")
		void encode_returnsTokenValueFromJwtEncoder() {
			// given
			Instant now = Instant.now();
			TokenModel model =
					new TokenModel(
							"http://auth.test", "1", now, now.plusSeconds(3600), Map.of("token_type", "access"));
			Jwt fakeJwt = fakeJwt("encoded-jwt-string", "1", Map.of("token_type", "access"));
			given(jwtEncoder.encode(any())).willReturn(fakeJwt);

			// when
			String result = manager.encode(model);

			// then
			assertThat(result).isEqualTo("encoded-jwt-string");
		}
	}

	@Nested
	@DisplayName("decode — JWT 문자열 → DecodedToken")
	class DecodeTest {

		@Test
		@DisplayName("decode() 성공 시 subject와 claims를 담은 DecodedToken 반환")
		void decode_success_returnsDecodedToken() {
			// given
			Map<String, Object> claimsMap = Map.of("token_type", "refresh");
			Jwt fakeJwt = fakeJwt("valid-jwt", "42", claimsMap);
			given(jwtDecoder.decode("valid-jwt")).willReturn(fakeJwt);

			// when
			DecodedToken decoded = manager.decode("valid-jwt");

			// then
			assertThat(decoded.subject()).isEqualTo("42");
			assertThat(decoded.claims()).containsEntry("token_type", "refresh");
		}

		@Test
		@DisplayName("JwtDecoder에서 JwtException 발생 시 InvalidTokenException으로 래핑")
		void decode_jwtException_wrapsAsInvalidTokenException() {
			// given
			given(jwtDecoder.decode("bad-jwt")).willThrow(new JwtException("Token expired"));

			// when / then
			assertThatThrownBy(() -> manager.decode("bad-jwt"))
					.isInstanceOf(InvalidTokenException.class)
					.hasMessageContaining("Token decode failed");
		}
	}
}
