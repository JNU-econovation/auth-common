# auth-lib-extraction - implementation

## 메타
- **작업명**: auth-lib-extraction
- **문서 타입**: implementation
- **작성일**: 2026-06-13
- **관련 문서** (같은 디렉터리):
  - todo.md

## 개요

auth-api의 application 계층(`LoginTokenService`, `LoginRedirectResolver`, 두 UseCase 인터페이스)을 신규 lib 모듈 `services/libs/login`(패키지 `com.econo.auth.login`)로 추출한다. 토큰 서명/검증 로직을 출력 포트 `TokenCodec`으로 추상화하여 lib이 `spring-security-oauth2` 의존을 갖지 않도록 하고, Nimbus 기반 구현체(`NimbusTokenCodecAdapter`)는 auth-api의 `config/security/`가 제공한다. 이 작업은 순수 리팩토링으로 엔드포인트·요청/응답·HTTP 상태 코드는 변경되지 않는다. Java 21 / Spring Boot 3.2.2 / Gradle Kotlin DSL 멀티모듈 프로젝트 위에서 ADR-0014 3계층 + 계층별 DIP 컨벤션을 따른다.

## 본문

### 모듈 / 패키지 배치

| 모듈 / 패키지 | 신규 / 변경 | 사유 |
|---------------|-------------|------|
| `services/libs/login/` | 신규 | auth application 계층 + TokenCodec 포트를 모듈 격리. spring-security-oauth2 의존 0 유지 |
| `services/libs/login/build.gradle.kts` | 신규 | `java-library` 플러그인 + member/service-client 의존. oauth2 의존 금지 |
| `services/libs/login/src/main/java/com/econo/auth/login/application/domain/` | 신규 | lib 도메인 객체 (Spring 타입 0) |
| `services/libs/login/src/main/java/com/econo/auth/login/application/repository/` | 신규 | 출력 포트 `TokenCodec` |
| `services/libs/login/src/main/java/com/econo/auth/login/application/usecase/` | 신규 | `LoginTokenUseCase`, `LoginRedirectUseCase` 입력 포트 (Jwt 타입 제거) |
| `services/libs/login/src/main/java/com/econo/auth/login/application/service/` | 신규 | `LoginTokenService`(재작성), `LoginRedirectResolver`(이동) |
| `services/libs/login/src/main/java/com/econo/auth/login/exception/` | 신규 | `InvalidTokenException`, `WrongTokenTypeException` |
| `services/libs/login/src/main/java/com/econo/auth/login/config/` | 신규 | `LoginAutoConfiguration` |
| `services/libs/login/src/main/resources/META-INF/spring/` | 신규 | `org.springframework.boot.autoconfigure.AutoConfiguration.imports` |
| `services/libs/login/src/test/java/com/econo/auth/login/application/service/` | 신규 | `LoginTokenServiceTest`(재작성 이동), `LoginRedirectResolverTest`(이동) |
| `settings.gradle.kts` | 변경 | `include("services:libs:login")` 추가 |
| `services/apis/auth-api/build.gradle.kts` | 변경 | `implementation(project(":services:libs:login"))` 추가 |
| `services/apis/auth-api/.../config/security/NimbusTokenCodecAdapter` | 신규 | `TokenCodec` 출력 포트 Nimbus 구현체. auth-api config/security 배치 |
| `services/apis/auth-api/.../presentation/controller/ReissueController` | 변경 | `JwtDecoder`/`Jwt` 제거. 두 예외 catch → 401 REFRESH_TOKEN_INVALID |
| `services/apis/auth-api/.../config/ApplicationServiceConfig` | 변경 | `loginRedirectResolver @Bean` 제거. import 갱신 |
| `services/apis/auth-api/.../config/security/SecurityConfig` | 변경 | import `com.econo.auth.login.application.usecase.*`로 갱신 |
| `services/apis/auth-api/.../config/security/JsonLoginAuthenticationFilter` | 변경 | import `com.econo.auth.login.application.usecase.*`로 갱신 |
| `services/apis/auth-api/src/main/java/com/econo/auth/api/application/` | 삭제 | usecase 2파일 + service 2파일 제거 |
| `services/apis/auth-api/src/test/java/.../application/service/` | 삭제 | `LoginTokenServiceTest`, `LoginRedirectResolverTest` lib로 이동 후 제거 |
| `services/apis/auth-api/src/test/.../config/security/NimbusTokenCodecAdapterTest` | 신규 | adapter 단위 테스트 |

---

### 구성 요소 설계

#### 모듈 / 패키지: `services/libs/login`

```
services/libs/login/
├── build.gradle.kts
└── src/
    ├── main/
    │   ├── java/com/econo/auth/login/
    │   │   ├── application/
    │   │   │   ├── domain/
    │   │   │   │   ├── TokenSpec           — AT/RT 인코딩 명세 (순수 record)
    │   │   │   │   └── DecodedToken        — 디코딩 결과 (순수 record)
    │   │   │   ├── repository/
    │   │   │   │   └── TokenCodec          — 출력 포트: encode/decode
    │   │   │   ├── usecase/
    │   │   │   │   ├── LoginTokenUseCase   — 입력 포트: issue/reissue/verifyRefreshTokenAndGetMemberId
    │   │   │   │   └── LoginRedirectUseCase — 입력 포트: resolve
    │   │   │   └── service/
    │   │   │       ├── LoginTokenService   — LoginTokenUseCase 구현체 (@Service)
    │   │   │       └── LoginRedirectResolver — LoginRedirectUseCase 구현체 (@Service)
    │   │   ├── exception/
    │   │   │   ├── InvalidTokenException   — decode 실패 시 throw
    │   │   │   └── WrongTokenTypeException — token_type 불일치 시 throw
    │   │   └── config/
    │   │       └── LoginAutoConfiguration   — @AutoConfiguration + @ComponentScan
    │   └── resources/META-INF/spring/
    │       └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
    └── test/
        └── java/com/econo/auth/login/application/service/
            ├── LoginTokenServiceTest       — TokenCodec mock 방식 재작성
            └── LoginRedirectResolverTest   — 6개 시나리오, import만 갱신
```

---

##### TokenSpec
- **타입**: Domain record (application/domain)
- **책임**: AT 또는 RT 인코딩에 필요한 모든 정보를 담는 불변 데이터 객체. Spring/JWT 타입 의존 없음.
- **주요 필드**: `String issuer`, `String subject`, `Instant issuedAt`, `Instant expiresAt`, `Map<String, Object> claims`
- **의존성**: 없음 (순수 Java record)
- **적용 컨벤션**:
  - `record`로 선언 — 불변성 자동 보장 (CONVENTION.md §2.3)
  - `claims`는 `Map.copyOf()`로 방어적 복사
  - Spring Framework 타입(`JwtClaimsSet` 등) import 금지
- **참조할 기존 코드**: `LoginTokenService.java:89-110` — AT 클레임 구성 (issuer, subject, issuedAt, expiresAt, claims 맵) 참고
- **연관 todo**: `[ ] application/domain/TokenSpec record 작성`

---

##### DecodedToken
- **타입**: Domain record (application/domain)
- **책임**: `TokenCodec.decode()` 반환 값. subject와 claims 맵만 담는 불변 데이터 객체.
- **주요 필드**: `String subject`, `Map<String, Object> claims`
- **의존성**: 없음 (순수 Java record)
- **적용 컨벤션**:
  - `record`로 선언 (CONVENTION.md §2.3)
  - `claims`는 `Map.copyOf()`로 방어적 복사
  - Spring Framework 타입(`Jwt` 등) import 금지
- **참조할 기존 코드**: `LoginTokenService.java:80-86` — `jwt.getSubject()`, `jwt.getClaimAsString(TOKEN_TYPE_CLAIM)` 참고
- **연관 todo**: `[ ] application/domain/DecodedToken record 작성`

---

##### TokenCodec
- **타입**: Output Port Interface (application/repository)
- **책임**: AT/RT 인코딩·디코딩 추상화. lib이 oauth2 구현체에 직접 의존하지 않도록 격리하는 DIP 경계.
- **주요 메서드/함수**:
  - `String encode(TokenSpec spec)` — TokenSpec을 JWT 문자열로 인코딩
  - `DecodedToken decode(String token)` — JWT 문자열을 DecodedToken으로 디코딩. 실패 시 `InvalidTokenException` throw
- **의존성**: `TokenSpec`, `DecodedToken`, `InvalidTokenException` (모두 lib 내부)
- **적용 컨벤션**:
  - 출력 포트 인터페이스 → `{Resource}Repository` 또는 서술적 이름 규칙 적용. `TokenCodec`은 역할 서술적 이름 (CONVENTION.md §1.2)
  - 메서드 시그니처에 Spring/Nimbus 타입 금지 (ARCHITECTURE.md 의존성 불변식 §3)
- **참조할 기존 코드**: `MemberRepository.java` — 출력 포트 인터페이스 패턴 참조
- **연관 todo**: `[ ] application/repository/TokenCodec 인터페이스 작성`

---

##### InvalidTokenException
- **타입**: Domain Exception (exception/)
- **책임**: `TokenCodec.decode()` 실패 시 throw. Spring 타입 의존 없음.
- **주요 메서드/함수**: 생성자 또는 정적 팩토리 메서드
- **의존성**: 없음 (순수 Java)
- **적용 컨벤션**:
  - 예외 클래스명 패턴 `{Domain}Exception` (CONVENTION.md §1.2)
  - 이 예외는 `ReissueController`에서 catch되어 401 `REFRESH_TOKEN_INVALID` 반환에 사용됨
  - `@ResponseStatus` 없음 — 컨트롤러에서 명시적으로 처리
- **연관 todo**: `[ ] exception/InvalidTokenException 작성`

---

##### WrongTokenTypeException
- **타입**: Domain Exception (exception/)
- **책임**: `LoginTokenService.verifyRefreshTokenAndGetMemberId()` 내부에서 `token_type`이 `"refresh"`가 아닐 때 throw.
- **의존성**: 없음 (순수 Java)
- **적용 컨벤션**:
  - 예외 클래스명 패턴 `{Domain}Exception` (CONVENTION.md §1.2)
  - `@ResponseStatus` 없음 — `ReissueController`에서 catch하여 401 처리
- **연관 todo**: `[ ] exception/WrongTokenTypeException 작성`

---

##### LoginTokenUseCase
- **타입**: Input Port Interface (application/usecase)
- **책임**: 토큰 발급/재발급/검증 계약 정의. 기존 `extractMemberIdFromRt(Jwt)` 메서드를 제거하고 `verifyRefreshTokenAndGetMemberId(String)` 시그니처로 교체하여 Jwt 타입 의존 제거.
- **주요 메서드/함수**:
  - `TokenPair issue(Member member)` — 로그인 시 AT+RT 발급
  - `TokenPair reissue(Long memberId)` — 재발급
  - `Long verifyRefreshTokenAndGetMemberId(String rawRt)` — RT 검증 후 memberId 반환. 디코딩 실패 시 `InvalidTokenException`, token_type 불일치 시 `WrongTokenTypeException` 전파
  - `record TokenPair(String accessToken, long accessExpiredAt, String refreshToken)` — 내부 record (기존 구조 유지)
- **의존성**: `com.econo.auth.member.application.domain.Member`, `InvalidTokenException`, `WrongTokenTypeException`
- **적용 컨벤션**:
  - 입력 포트 인터페이스 `{Action}UseCase` 패턴 (CONVENTION.md §1.2)
  - `TokenPair` 내부 record는 기존 위치 유지 — `ReissueController`·`JsonLoginAuthenticationFilter`가 `LoginTokenUseCase.TokenPair`로 참조
  - `org.springframework.security.oauth2.jwt.Jwt` import 금지
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/application/usecase/LoginTokenUseCase.java:1-46` — 전체 구조 참고, `extractMemberIdFromRt(Jwt)` 메서드를 `verifyRefreshTokenAndGetMemberId(String)`으로 교체
- **연관 todo**: `[ ] application/usecase/LoginTokenUseCase 인터페이스 작성`

---

##### LoginRedirectUseCase
- **타입**: Input Port Interface (application/usecase)
- **책임**: clientId 기반 redirect 목적지 결정 계약. 패키지만 변경, 내용 동일.
- **주요 메서드/함수**: `String resolve(String clientId, String defaultUrl)`
- **의존성**: 없음 (순수 Java 타입)
- **적용 컨벤션**:
  - 기존 인터페이스와 동일한 내용, 패키지만 `com.econo.auth.login.application.usecase`로 변경
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/application/usecase/LoginRedirectUseCase.java:1-19` — 그대로 복사
- **연관 todo**: `[ ] application/usecase/LoginRedirectUseCase 인터페이스 작성`

---

##### LoginTokenService (재작성)
- **타입**: Service (application/service), `@Service`
- **책임**: `LoginTokenUseCase` 구현체. `JwtEncoder`/`JwtDecoder` 대신 `TokenCodec` 포트를 사용하여 AT/RT 발급·검증. `@Value` issuer/expiry 유지.
- **주요 메서드/함수**:
  - `TokenPair issue(Member member)` — AT/RT `TokenSpec` 조립 후 `codec.encode()` 2회 호출
  - `TokenPair reissue(Long memberId)` — `memberRepository.findById()` → `issue()` 위임
  - `Long verifyRefreshTokenAndGetMemberId(String rawRt)` — `codec.decode(rawRt)` → `claims.get("token_type")` 검사 → memberId 반환
- **현재 코드 → 목표 매핑**:

  | 현재 (auth-api) | 목표 (auth lib) |
  |-----------------|-----------------|
  | `private final JwtEncoder jwtEncoder` | 제거 |
  | `private final JwtDecoder jwtDecoder` | 제거 |
  | `private final TokenCodec codec` | 신규 주입 |
  | `extractMemberIdFromRt(Jwt jwt)` | `verifyRefreshTokenAndGetMemberId(String rawRt)` |
  | `encodeAt()` 내 `JwtClaimsSet.builder()...jwtEncoder.encode()` | `codec.encode(atSpec)` |
  | `encodeRt()` 내 `JwtClaimsSet.builder()...jwtEncoder.encode()` | `codec.encode(rtSpec)` |
  | `jwt.getClaimAsString(TOKEN_TYPE_CLAIM)` | `decoded.claims().get(TOKEN_TYPE_CLAIM)` |
  | `throw new IllegalArgumentException("Not a refresh token")` | `throw new WrongTokenTypeException(...)` |

- **AT TokenSpec 클레임 구성** (기존 `encodeAt()` 로직 보존):
  - `issuer`, `subject = member.getId()`, `issuedAt = now`, `expiresAt = now + atExpirySeconds`
  - claims: `memberId`, `loginId`, `name`, `generation`, `status`, `roles = List.of(member.getRole())`, `token_type = "access"`
- **RT TokenSpec 클레임 구성** (기존 `encodeRt()` 로직 보존):
  - `issuer`, `subject = member.getId()`, `issuedAt = now`, `expiresAt = now + rtExpirySeconds`
  - claims: `token_type = "refresh"` (roles 없음)
- **의존성**: `TokenCodec`, `MemberRepository`, `@Value` 3종 (`AUTH_ISSUER_URI`, `auth.token.at-expiry-seconds`, `auth.token.rt-expiry-seconds`)
- **적용 컨벤션**:
  - `@Service` 자동 등록 — `LoginAutoConfiguration` `@ComponentScan`으로 스캔됨 (ApplicationServiceConfig 수동 등록 불필요)
  - `@RequiredArgsConstructor` 사용 불가 — `@Value` 주입이 있어 직접 생성자 작성 (기존 방식 유지, CONVENTION.md §2.2)
  - `private static final String TOKEN_TYPE_CLAIM`, `ACCESS`, `REFRESH` 상수 유지
  - Spring Security OAuth2 import 금지 (ARCHITECTURE.md 의존성 불변식)
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/application/service/LoginTokenService.java:1-131` — 전체 구조 참고
- **연관 todo**: `[ ] application/service/LoginTokenService 작성 (@Service)`

---

##### LoginRedirectResolver (이동)
- **타입**: Service (application/service), `@Service`
- **책임**: `LoginRedirectUseCase` 구현체. 기존 auth-api 로직 동일, 패키지와 등록 방식 변경.
- **주요 메서드/함수**: `String resolve(String clientId, String defaultUrl)` — 6가지 분기 포함 fail-safe 로직
- **현재 코드 → 목표 매핑**:
  - 기존: `ApplicationServiceConfig.loginRedirectResolver @Bean` 수동 등록 + `@RequiredArgsConstructor` 일반 클래스
  - 목표: `@Service` 추가 → `LoginAutoConfiguration @ComponentScan`으로 자동 등록. `ApplicationServiceConfig.loginRedirectResolver @Bean` 제거.
- **의존성**: `ClientRedirectUriUseCase` (service-client lib)
- **적용 컨벤션**:
  - `@Slf4j` + `@RequiredArgsConstructor` 유지 (CONVENTION.md §2.2)
  - `@Service` 추가만이 유일한 변경
  - 로직(6가지 시나리오) 변경 없음
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/application/service/LoginRedirectResolver.java:1-65` — 그대로 복사, `@Service` 추가, 패키지 변경
- **연관 todo**: `[ ] application/service/LoginRedirectResolver 작성 (@Service)`

---

##### LoginAutoConfiguration
- **타입**: AutoConfiguration (config/)
- **책임**: lib 모듈 자기 스캔. `@ComponentScan("com.econo.auth.login")`로 `LoginTokenService`·`LoginRedirectResolver`를 소비자(auth-api) 컨텍스트에 등록.
- **의존성**: 없음
- **적용 컨벤션**:
  - `@AutoConfiguration` + `@ComponentScan` 패턴 — `MemberAutoConfiguration` 동일 구조 미러링
  - `@EnableJpaRepositories` / `@EntityScan` 없음 — auth lib에 JPA 엔티티가 없으므로
  - `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`에 `com.econo.auth.login.config.LoginAutoConfiguration` 1행 등록
  - 설정 클래스명 패턴 `{Domain}AutoConfiguration` (CONVENTION.md §1.2)
- **참조할 기존 코드**: `services/libs/member/src/main/java/com/econo/auth/member/config/MemberAutoConfiguration.java:1-19` — `@AutoConfiguration` + `@ComponentScan` 패턴 미러링
- **연관 todo**: `[ ] config/LoginAutoConfiguration 작성`

---

#### 모듈 / 패키지: `services/apis/auth-api` (변경)

```
services/apis/auth-api/src/main/java/com/econo/auth/api/
├── config/
│   ├── ApplicationServiceConfig     — loginRedirectResolver @Bean 제거, signupService @Bean 유지
│   ├── RsaKeyConfig                 — 변경 없음 (jwtEncoder/jwtDecoder 빈 계속 제공)
│   └── security/
│       ├── NimbusTokenCodecAdapter  — 신규: TokenCodec 구현체 (@Component)
│       ├── SecurityConfig           — import 갱신: com.econo.auth.login.application.usecase.*
│       └── JsonLoginAuthenticationFilter — import 갱신: com.econo.auth.login.application.usecase.*
└── presentation/
    └── controller/
        └── ReissueController        — JwtDecoder/Jwt 제거, 두 예외 catch로 교체
```

---

##### NimbusTokenCodecAdapter
- **타입**: Component (config/security, `@Component`)
- **책임**: `TokenCodec` 출력 포트의 Nimbus/Spring Security OAuth2 구현체. lib과 oauth2 사이의 격리 경계.
- **주요 메서드/함수**:
  - `String encode(TokenSpec spec)` — `TokenSpec` → `JwtClaimsSet` + `JwsHeader.RS256` 조립 → `jwtEncoder.encode(JwtEncoderParameters.from(...)).getTokenValue()`
  - `DecodedToken decode(String token)` — `jwtDecoder.decode(token)` → `new DecodedToken(jwt.getSubject(), jwt.getClaims())`; `JwtException` 발생 시 `InvalidTokenException`으로 래핑
- **현재 코드 → 목표 매핑**: `LoginTokenService.encodeAt()`·`encodeRt()` 내부의 `JwtClaimsSet.builder()`·`JwsHeader.with(RS256)`·`jwtEncoder.encode()` 로직을 이 어댑터로 이동.

  ```java
  // encode(): 기존 LoginTokenService.encodeAt/encodeRt의 JwtClaimsSet 조립 + jwtEncoder 호출 이동
  JwtClaimsSet claims = JwtClaimsSet.builder()
      .issuer(spec.issuer())
      .subject(spec.subject())
      .issuedAt(spec.issuedAt())
      .expiresAt(spec.expiresAt())
      .claims(c -> c.putAll(spec.claims()))
      .build();
  return jwtEncoder.encode(
      JwtEncoderParameters.from(
          JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
      .getTokenValue();

  // decode(): 기존 ReissueController의 jwtDecoder.decode() 로직 이동
  try {
      Jwt jwt = jwtDecoder.decode(token);
      return new DecodedToken(jwt.getSubject(), jwt.getClaims());
  } catch (JwtException e) {
      throw new InvalidTokenException("Token decode failed: " + e.getMessage(), e);
  }
  ```

- **의존성**: `JwtEncoder` (RsaKeyConfig 빈), `JwtDecoder` (RsaKeyConfig 빈), `TokenCodec`, `DecodedToken`, `TokenSpec`, `InvalidTokenException`
- **적용 컨벤션**:
  - `config/security/` 배치 — Spring Security OAuth2 결합 클래스 전용 패키지 (ADR-0014, CONVENTION.md §1.1)
  - `@Component` 자동 등록 — auth-api의 `@SpringBootApplication` 컴포넌트 스캔 범위
  - 어댑터 클래스명 패턴 `{Algo}{Role}Adapter` (CONVENTION.md §1.2) — `NimbusTokenCodecAdapter`
  - `@RequiredArgsConstructor` 사용 (Lombok 의존성 주입)
- **참조할 기존 코드**:
  - `services/apis/auth-api/src/main/java/com/econo/auth/api/application/service/LoginTokenService.java:88-130` — `encodeAt()`·`encodeRt()` JwtClaimsSet 조립 로직
  - `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/controller/ReissueController.java:68-74` — `jwtDecoder.decode()` + `JwtException` catch 패턴
- **연관 todo**: `[ ] config/security/NimbusTokenCodecAdapter 신설`

---

##### ReissueController (변경)
- **타입**: Controller (presentation/controller)
- **책임**: AT/RT 재발급·로그아웃 HTTP 처리. `JwtDecoder` 직접 의존 제거 후 `verifyRefreshTokenAndGetMemberId(String)`으로 흐름 단순화.
- **현재 코드 → 목표 매핑**:

  현재 (auth-api, `ReissueController.java:38-83`):
  ```java
  private final LoginTokenUseCase loginTokenUseCase;
  private final JwtDecoder jwtDecoder;            // ← 제거
  // ...
  Jwt jwt;
  try {
      jwt = jwtDecoder.decode(rawRt);             // ← 제거
  } catch (JwtException e) {
      return 401 REFRESH_TOKEN_INVALID;           // ← 통합
  }
  Long memberId;
  try {
      memberId = loginTokenUseCase.extractMemberIdFromRt(jwt); // ← 제거
  } catch (IllegalArgumentException e) {
      return 401 REFRESH_TOKEN_INVALID;           // ← 통합
  }
  ```

  목표:
  ```java
  private final LoginTokenUseCase loginTokenUseCase;
  // JwtDecoder 필드 없음
  // ...
  Long memberId;
  try {
      memberId = loginTokenUseCase.verifyRefreshTokenAndGetMemberId(rawRt);
  } catch (InvalidTokenException | WrongTokenTypeException e) {
      return ResponseEntity.status(401)
          .body(new ErrorResponse("REFRESH_TOKEN_INVALID", "유효하지 않은 Refresh token입니다."));
  }
  ```

- **동작 보존 요건**:
  - `rawRt == null || rawRt.isBlank()` → 401 `REFRESH_TOKEN_MISSING` (컨트롤러 유지, 변경 없음)
  - 디코딩 실패(`InvalidTokenException`) → 401 `REFRESH_TOKEN_INVALID` (기존 `JwtException` 경로 보존)
  - token_type 불일치(`WrongTokenTypeException`) → 401 `REFRESH_TOKEN_INVALID` (기존 `IllegalArgumentException` 경로 보존, 메시지는 구분 가능하지만 errorCode 동일)
  - import에서 `org.springframework.security.oauth2.jwt.Jwt`, `org.springframework.security.oauth2.jwt.JwtDecoder`, `org.springframework.security.oauth2.jwt.JwtException` 제거
  - import에서 `com.econo.auth.login.exception.InvalidTokenException`, `com.econo.auth.login.exception.WrongTokenTypeException` 추가
  - import `com.econo.auth.api.application.usecase.LoginTokenUseCase` → `com.econo.auth.login.application.usecase.LoginTokenUseCase`
- **적용 컨벤션**:
  - `@RequiredArgsConstructor` 유지 (JwtDecoder 필드 제거 후 final 필드만 남음)
  - presentation 계층은 usecase 인터페이스에만 의존 (ADR-0014 의존성 불변식)
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/controller/ReissueController.java:34-98`
- **연관 todo**: `[ ] ReissueController 수정`

---

##### ApplicationServiceConfig (변경)
- **타입**: Config (config/)
- **책임**: `loginRedirectResolver @Bean` 제거 (lib `@Service` 자동 등록 전환). `signupService @Bean` 유지.
- **현재 코드 → 목표 매핑**:
  - `com.econo.auth.api.application.service.LoginRedirectResolver` import 제거
  - `loginRedirectResolver()` `@Bean` 메서드 전체 제거 (`ApplicationServiceConfig.java:38-44`)
  - `signupService @Bean` 유지 (`ApplicationServiceConfig.java:27-32`)
- **연관 todo**: `[ ] ApplicationServiceConfig 수정`

---

##### SecurityConfig (변경)
- **타입**: Security Config (config/security)
- **변경 범위**: import 2줄만 변경
  - `com.econo.auth.api.application.usecase.LoginRedirectUseCase` → `com.econo.auth.login.application.usecase.LoginRedirectUseCase`
  - `com.econo.auth.api.application.usecase.LoginTokenUseCase` → `com.econo.auth.login.application.usecase.LoginTokenUseCase`
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/config/security/SecurityConfig.java:3-5`
- **연관 todo**: `[ ] SecurityConfig import 갱신`

---

##### JsonLoginAuthenticationFilter (변경)
- **타입**: Security Filter (config/security)
- **변경 범위**: import 3줄 변경
  - `com.econo.auth.api.application.usecase.LoginRedirectUseCase` → `com.econo.auth.login.application.usecase.LoginRedirectUseCase`
  - `com.econo.auth.api.application.usecase.LoginTokenUseCase` → `com.econo.auth.login.application.usecase.LoginTokenUseCase`
  - `com.econo.auth.api.application.usecase.LoginTokenUseCase.TokenPair` → `com.econo.auth.login.application.usecase.LoginTokenUseCase.TokenPair`
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/config/security/JsonLoginAuthenticationFilter.java:3-5`
- **연관 todo**: `[ ] JsonLoginAuthenticationFilter import 갱신`

---

#### 모듈 / 패키지: `services/apis/auth-api` — 테스트

```
services/apis/auth-api/src/test/java/com/econo/auth/api/
└── config/security/
    └── NimbusTokenCodecAdapterTest     — 신규: adapter 단위 테스트

# 삭제 대상 (lib으로 이동 후 제거)
services/apis/auth-api/src/test/java/com/econo/auth/api/application/service/
├── LoginTokenServiceTest.java          — lib으로 이동 후 삭제
└── LoginRedirectResolverTest.java      — lib으로 이동 후 삭제
```

##### NimbusTokenCodecAdapterTest
- **타입**: Unit Test
- **책임**: `NimbusTokenCodecAdapter`의 encode/decode/예외 래핑 검증
- **주요 시나리오**:
  - `encode()`: `JwtEncoder.encode()` 호출 후 token 문자열 반환 검증
  - `decode()` 성공: `DecodedToken(subject, claims)` 반환 검증
  - `decode()` 실패: `JwtException` → `InvalidTokenException` 래핑 검증
- **적용 컨벤션**: `@ExtendWith(MockitoExtension.class)`, `@Nested`, `@DisplayName` 한글, Given-When-Then, AssertJ (CONVENTION.md §5)
- **연관 todo**: `[ ] NimbusTokenCodecAdapter 단위 테스트 신설`

---

##### LoginTokenServiceTest (재작성 후 lib 이동)
- **이동 경로**: `services/apis/auth-api/src/test/.../application/service/LoginTokenServiceTest.java` → `services/libs/login/src/test/java/com/econo/auth/login/application/service/LoginTokenServiceTest.java`
- **재작성 핵심**:
  - `@Mock JwtEncoder`, `@Mock JwtDecoder` → `@Mock TokenCodec`
  - `ArgumentCaptor<JwtEncoderParameters>` → `ArgumentCaptor<TokenSpec>`
  - AT 클레임 검증: `captor.getValue().claims().get("roles")`, `token_type == "access"`, memberId/loginId/name/generation/status 포함 여부
  - RT 클레임 검증: `token_type == "refresh"`, roles 없음
  - `verifyRefreshTokenAndGetMemberId("access-token")` → `WrongTokenTypeException` 검증
  - `codec.decode()` 실패 → `InvalidTokenException` 전파 검증
- **연관 todo**: `[ ] LoginTokenServiceTest 재작성 및 이동`

---

##### LoginRedirectResolverTest (lib 이동)
- **이동 경로**: `services/apis/auth-api/src/test/.../application/service/LoginRedirectResolverTest.java` → `services/libs/login/src/test/java/com/econo/auth/login/application/service/LoginRedirectResolverTest.java`
- **변경 범위**: 패키지 선언 + import 2줄만 변경. 6개 시나리오 내용 동일.
- **연관 todo**: `[ ] LoginRedirectResolverTest 이동`

---

### 호출 흐름

#### 정상 경로 — 로그인 (issue)

```
POST /api/v1/auth/login
  → JsonLoginAuthenticationFilter.attemptAuthentication()
      (import: com.econo.auth.login.application.usecase.LoginTokenUseCase)
  → JsonLoginAuthenticationFilter.successfulAuthentication()
  → loginTokenUseCase.issue(member)          [LoginTokenUseCase 입력 포트]
  → LoginTokenService.issue(member)          [lib @Service]
      → codec.encode(atSpec)                 [TokenCodec 출력 포트]
      → NimbusTokenCodecAdapter.encode()     [auth-api config/security]
          → JwtClaimsSet.builder()...
          → jwtEncoder.encode(params)        [RsaKeyConfig 빈]
          → JWT 문자열 반환
      → codec.encode(rtSpec)                 [동일 경로]
      → TokenPair 반환
  → [WEB] 쿠키 + 302 리다이렉트
  → [APP] 200 OK + body
```

#### 정상 경로 — 재발급 (reissue)

```
POST /api/v1/auth/reissue
  → ReissueController.reissue()
  → rawRt null/blank 체크 → pass
  → loginTokenUseCase.verifyRefreshTokenAndGetMemberId(rawRt)
  → LoginTokenService.verifyRefreshTokenAndGetMemberId(rawRt)
      → codec.decode(rawRt)                  [TokenCodec 출력 포트]
      → NimbusTokenCodecAdapter.decode()
          → jwtDecoder.decode(rawRt)         [RsaKeyConfig 빈]
          → DecodedToken(subject, claims) 반환
      → claims.get("token_type") == "refresh" 검증 → pass
      → Long.valueOf(decoded.subject()) 반환
  → loginTokenUseCase.reissue(memberId)
  → LoginTokenService.reissue(memberId)
      → memberRepository.findById(memberId)
      → issue(member) → TokenPair
  → [WEB] 쿠키 + 200 OK
  → [APP] 200 OK + body
```

#### 예외 / 실패 경로 — 재발급

```
[경로 1] rawRt == null || rawRt.isBlank()
  → ReissueController: 401 {"errorCode":"REFRESH_TOKEN_MISSING","message":"Refresh token이 없습니다."}

[경로 2] NimbusTokenCodecAdapter.decode() 에서 JwtException 발생
  (만료, 서명 불일치, 형식 오류 등)
  → NimbusTokenCodecAdapter: JwtException → throw new InvalidTokenException(...)
  → LoginTokenService.verifyRefreshTokenAndGetMemberId(): InvalidTokenException 전파
  → ReissueController: catch(InvalidTokenException e)
  → 401 {"errorCode":"REFRESH_TOKEN_INVALID","message":"유효하지 않은 Refresh token입니다."}

[경로 3] decode 성공했으나 token_type != "refresh" (AT로 재발급 시도)
  → LoginTokenService.verifyRefreshTokenAndGetMemberId(): throw new WrongTokenTypeException(...)
  → ReissueController: catch(WrongTokenTypeException e)
  → 401 {"errorCode":"REFRESH_TOKEN_INVALID","message":"유효하지 않은 Refresh token입니다."}

[경로 4] RT 유효하고 token_type=refresh이나 회원 탈퇴 케이스
  → LoginTokenService.reissue(): memberRepository.findById() empty
  → MemberNotFoundException 발생
  → GlobalExceptionHandler → 404 MEMBER_NOT_FOUND
```

#### 예외 / 실패 경로 — NimbusTokenCodecAdapter.encode()

```
jwtEncoder.encode() 실패 (RSA 키 로드 실패 등)
  → JwtEncodingException (JwtException 하위) 발생
  → NimbusTokenCodecAdapter: 이 케이스는 encode()에서 래핑 불필요 — 런타임 예외로 전파
    (기존 LoginTokenService도 encode 실패 시 별도 처리 없음)
  → 500 Internal Server Error
```

---

### 빌드 설정 변경

#### `settings.gradle.kts` (변경)

현재:
```kotlin
// === Libs (공유 라이브러리) ===
include("services:libs:member")
include("services:libs:common-infra")
include("services:libs:service-client")
```

목표:
```kotlin
// === Libs (공유 라이브러리) ===
include("services:libs:member")
include("services:libs:common-infra")
include("services:libs:service-client")
include("services:libs:login")
```

#### `services/libs/login/build.gradle.kts` (신규)

```kotlin
plugins {
    `java-library`
}

dependencies {
    implementation(project(":services:libs:member"))
    implementation(project(":services:libs:service-client"))
    implementation("org.springframework.boot:spring-boot-starter")
    // spring-security-oauth2-jose, spring-security-oauth2-jwt 의존 추가 금지
}
```

주의: `spring-boot-starter`가 `spring-context`를 포함하므로 `@Service`, `@AutoConfiguration`, `@ComponentScan` 사용 가능. Lombok은 루트 `build.gradle.kts` `subprojects {}` 블록에서 `compileOnly`/`annotationProcessor`로 전이 적용되므로 별도 선언 불필요.

#### `services/apis/auth-api/build.gradle.kts` (변경)

추가:
```kotlin
implementation(project(":services:libs:login"))
```

기존 `implementation(project(":services:libs:member"))`, `implementation(project(":services:libs:service-client"))` 유지.

---

### 컨벤션 준수 항목

- **네이밍**:
  - 패키지: `com.econo.auth.login` (역도메인, 소문자, 연속) (CONVENTION.md §1.1)
  - 클래스: `TokenCodec`(출력 포트 서술적 이름), `LoginAutoConfiguration`(`{Domain}AutoConfiguration`), `NimbusTokenCodecAdapter`(`{Algo}{Role}Adapter`), `InvalidTokenException`/`WrongTokenTypeException`(`{Domain}Exception`) (CONVENTION.md §1.2)
  - UseCase 인터페이스: `LoginTokenUseCase`, `LoginRedirectUseCase` (`{Action}UseCase`) (CONVENTION.md §1.2)

- **의존성 주입**:
  - `LoginTokenService`: `@Value` 포함으로 직접 생성자 작성 (CONVENTION.md §2.2 예외 케이스)
  - `LoginRedirectResolver`, `NimbusTokenCodecAdapter`: `@RequiredArgsConstructor` (CONVENTION.md §2.2)
  - `LoginAutoConfiguration`: 생성자 없음 (설정 클래스)

- **예외 처리**:
  - `NimbusTokenCodecAdapter.decode()`: `JwtException` catch → `InvalidTokenException`으로 래핑 (CONVENTION.md §3.2 패턴)
  - `LoginTokenService.verifyRefreshTokenAndGetMemberId()`: 조건 불일치 시 `WrongTokenTypeException` throw — 정적 팩토리 메서드 권장 (CONVENTION.md §3.1)
  - `ReissueController`: `catch(InvalidTokenException | WrongTokenTypeException e)` 멀티 캐치로 401 일원화

- **불변성**:
  - `TokenSpec`, `DecodedToken`: `record`로 선언하여 불변성 보장 (CONVENTION.md §2.3)
  - `TokenSpec.claims`, `DecodedToken.claims`: `Map.copyOf()` 방어적 복사 (CONVENTION.md §2.3)

- **테스트 패턴**:
  - `@ExtendWith(MockitoExtension.class)` + `@Nested` + `@DisplayName` 한글 (CONVENTION.md §5.1)
  - Given-When-Then 주석 구분 (CONVENTION.md §5.2)
  - `ArgumentCaptor<TokenSpec>`으로 encode 호출 검증

- **Javadoc**:
  - 모든 public 클래스·메서드에 Javadoc 필수 (CONVENTION.md §4.1)
  - private 메서드는 `/** 한 줄 설명 */` (CONVENTION.md §4.2)

- **auth lib spring-security-oauth2 의존 0**:
  - `build.gradle.kts`에 `spring-security-oauth2-jose`, `spring-security-oauth2-jwt`, `spring-boot-starter-oauth2-authorization-server` 의존 추가 금지
  - 검증: `./gradlew :services:libs:login:dependencies | grep spring-security-oauth2` 결과 없음 확인

- **iCloud 환경**:
  - 파일 이동/신설 후 `./gradlew clean build`로 빌드 전 `*\ 2.java` 중복본 검사 및 `rm` 제거 (Memory: iCloud 중복 파일 함정)

---

## 체크리스트
- [x] todo의 모든 구현 작업이 구성 요소로 매핑됨
- [x] 모든 구성 요소가 적절한 모듈/계층에 배치됨
- [x] 모든 구성 요소가 적용 컨벤션을 명시함
- [x] 호출 흐름에 빠진 분기가 없음 (정상/예외 모두)

## 참고
- `docs/ARCHITECTURE.md` — 모듈 구조, 3계층 계층 모델, 의존성 규칙
- `docs/CONVENTION.md` — 패키지·클래스·메서드 네이밍, Lombok, 예외, 테스트 컨벤션
- `docs/adr/0014-3-layer-dip-architecture.md` — 3계층 DIP 채택 근거 및 config/security 배치 기준
- `services/libs/member/src/main/java/com/econo/auth/member/config/MemberAutoConfiguration.java` — LoginAutoConfiguration 참조 모델
- `services/libs/member/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` — imports 파일 형식 참조
- `services/apis/auth-api/src/main/java/com/econo/auth/api/application/service/LoginTokenService.java` — 추출 원본 (클레임 구성 로직)
- `services/apis/auth-api/src/main/java/com/econo/auth/api/application/service/LoginRedirectResolver.java` — 추출 원본 (6가지 분기 로직)
- `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/controller/ReissueController.java` — 변경 원본 (decode/catch 패턴)
- `services/apis/auth-api/src/main/java/com/econo/auth/api/config/RsaKeyConfig.java` — jwtEncoder/jwtDecoder 빈 제공자
- `services/apis/auth-api/src/test/java/com/econo/auth/api/application/service/LoginTokenServiceTest.java` — 재작성 원본
- `services/apis/auth-api/src/test/java/com/econo/auth/api/application/service/LoginRedirectResolverTest.java` — 이동 원본 (6개 시나리오)
