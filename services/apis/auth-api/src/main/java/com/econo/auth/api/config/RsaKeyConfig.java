package com.econo.auth.api.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;

/**
 * RSA 키 설정 — PEM 환경변수 로드 후 JWKSource, JwtEncoder 빈 등록
 *
 * <p>{@code RSA_PRIVATE_KEY} (PKCS#8 PEM, {@code BEGIN PRIVATE KEY}) 와 {@code RSA_PUBLIC_KEY}
 * (X.509 PEM, {@code BEGIN PUBLIC KEY}) 환경변수에서 RSA 키페어를 로드한다. 키 로드 실패 시 {@link RuntimeException}을
 * throw하여 애플리케이션 기동을 중단한다.
 */
@Slf4j
@Configuration
public class RsaKeyConfig {

	@Value("${RSA_PRIVATE_KEY}")
	private String rsaPrivateKeyPem;

	@Value("${RSA_PUBLIC_KEY}")
	private String rsaPublicKeyPem;

	/**
	 * JWK Source 빈 등록 — SAS 및 JwtEncoder가 서명에 사용
	 *
	 * @return {@link JWKSource}
	 */
	@Bean
	public JWKSource<SecurityContext> jwkSource() {
		try {
			RSAPrivateKey privateKey = parsePrivateKey(rsaPrivateKeyPem);
			RSAPublicKey publicKey = parsePublicKey(rsaPublicKeyPem);

			// 고정 kid 사용 — 기동마다 kid가 바뀌면 기발급 토큰의 JWKS kid 매칭이 영구 실패함
			RSAKey rsaKey =
					new RSAKey.Builder(publicKey)
							.privateKey(privateKey)
							.keyID("econo-auth-rsa-key-v1")
							.build();

			JWKSet jwkSet = new JWKSet(rsaKey);
			return new ImmutableJWKSet<>(jwkSet);
		} catch (Exception e) {
			log.error("Failed to load RSA key pair from environment variables: {}", e.getMessage());
			throw new RuntimeException("Failed to initialize RSA key pair", e);
		}
	}

	/**
	 * JwtEncoder 빈 등록 — SAS가 Access/ID 토큰 서명에 사용
	 *
	 * @param jwkSource JWK Source
	 * @return {@link JwtEncoder}
	 */
	@Bean
	public JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwkSource) {
		return new NimbusJwtEncoder(jwkSource);
	}

	/**
	 * JwtDecoder 빈 등록 — SAS 내부 토큰 검증용
	 *
	 * @param jwkSource JWK Source
	 * @return {@link JwtDecoder}
	 */
	@Bean
	public JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
		return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
	}

	/** PKCS#8 PEM 문자열 → RSAPrivateKey 파싱 */
	private RSAPrivateKey parsePrivateKey(String pem) throws Exception {
		String stripped =
				pem.replace("-----BEGIN PRIVATE KEY-----", "")
						.replace("-----END PRIVATE KEY-----", "")
						.replaceAll("\\s+", "");
		byte[] keyBytes = Base64.getDecoder().decode(stripped);
		PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
		KeyFactory kf = KeyFactory.getInstance("RSA");
		return (RSAPrivateKey) kf.generatePrivate(spec);
	}

	/** X.509 PEM 문자열 → RSAPublicKey 파싱 */
	private RSAPublicKey parsePublicKey(String pem) throws Exception {
		String stripped =
				pem.replace("-----BEGIN PUBLIC KEY-----", "")
						.replace("-----END PUBLIC KEY-----", "")
						.replaceAll("\\s+", "");
		byte[] keyBytes = Base64.getDecoder().decode(stripped);
		X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
		KeyFactory kf = KeyFactory.getInstance("RSA");
		return (RSAPublicKey) kf.generatePublic(spec);
	}
}
