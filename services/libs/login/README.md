# login

로그인 토큰 발급·재발급·리다이렉트 결정 라이브러리. `LoginTokenUseCase`(AT/RT 발급·RT 검증), `LoginRedirectUseCase`(clientId 기반 리다이렉트 결정) 입력 포트와 서비스 구현체를 포함한다. Spring Boot AutoConfiguration(`LoginAutoConfiguration`)으로 자기 스캔하며, JWT 서명·검증은 출력 포트 `TokenEncoder`/`TokenDecoder`로 추상화하여 소비자 앱이 구현체를 제공한다.

---

## Quick Reference

| 항목 | 값 |
|------|-----|
| 패키지 루트 | `com.econo.auth.login` |
| Gradle 의존 경로 | `implementation(project(":services:libs:login"))` |
| 주요 연관 모듈 | `member` (회원 조회), `service-client` (redirectUri 조회), `auth-api` (소비자, `TokenEncoder`/`TokenDecoder` 구현체 제공) |
| AutoConfiguration | `com.econo.auth.login.config.LoginAutoConfiguration` |
| API 엔드포인트 | 해당 없음 — libs 모듈. 소비자: `JsonLoginAuthenticationFilter` (`POST /api/v1/auth/login`), `ReissueController` (`POST /api/v1/auth/reissue`) |

---

## 비즈니스 규칙

- **토큰 포트 (TokenEncoder/TokenDecoder)**: 이 모듈은 `spring-security-oauth2-jose` / `spring-security-oauth2-jwt`에 직접 의존하지 않는다. JWT 서명·검증 책임은 `TokenEncoder`(서명)·`TokenDecoder`(검증) 출력 포트(`application/repository/`)로 위임한다. 두 포트의 구현체(`NimbusTokenManager`, 단일 클래스가 둘 다 구현)는 소비자 앱(`auth-api`)의 `config/security/`에 위치한다.
- **AT 클레임 구성**: `LoginTokenService.encodeAt()`는 `memberId`, `loginId`, `name`, `generation`, `status`, `roles`, `token_type=access`를 클레임으로 포함한다. API Gateway의 `BearerToPassportFilter`가 이 클레임을 읽어 Passport를 빌드한다.
- **RT 클레임 구성**: RT에는 `token_type=refresh` 클레임만 포함한다. 회원 정보는 포함하지 않는다.
- **RT 검증 순서**: `verifyRefreshTokenAndGetMemberId(rawRt)` — `TokenDecoder.decode()`로 서명·만료 검증 → `token_type` 클레임이 `refresh`인지 확인 → `subject`(memberId 문자열)를 `Long`으로 반환. `token_type`이 `refresh`가 아니면 `WrongTokenTypeException`.
- **리다이렉트 결정 우선순위**: `LoginRedirectResolver.resolve(clientId, defaultUrl)`는 clientId가 null·blank이면 즉시 `defaultUrl`을 반환한다. clientId가 있으면 `ClientRedirectUriUseCase.findByClientId()`로 조회한다. 등록된 redirect_uri가 여러 개이면 알파벳 오름차순 정렬 후 첫 번째를 반환한다. `InvalidClientException`(미등록 clientId)이거나 redirect_uri가 비어 있으면 `defaultUrl`을 반환한다. 그 외 예상치 못한 예외도 `defaultUrl`로 fail-safe.
- ⚠️ **open redirect 방어**: `LoginRedirectResolver`는 user-supplied URL을 사용하지 않는다. 리다이렉트 목적지는 항상 DB에 등록된 redirect_uri 또는 설정값(`auth.redirect.default-url`)이다.
- **토큰 만료 설정**: AT 만료는 `auth.token.at-expiry-seconds`(기본 3600초), RT 만료는 `auth.token.rt-expiry-seconds`(기본 2592000초)로 설정한다. `LoginTokenService` 생성자가 `@Value`로 주입받는다. issuer는 `AUTH_ISSUER_URI`(기본 `http://localhost:8081`)를 `@Value("${AUTH_ISSUER_URI:http://localhost:8081}")`로 주입받아 AT/RT 클레임의 `iss`로 사용한다. API Gateway의 `BearerToPassportFilter`가 JWKS 검증 시 이 값을 `iss` 클레임과 비교하므로, Gateway의 `AUTH_ISSUER_URI` 설정과 일치해야 한다.
- ⚠️ **`LoginAutoConfiguration`은 JPA 스캔 없음**: 이 모듈에 JPA 엔티티가 없으므로 `@EnableJpaRepositories` / `@EntityScan`을 선언하지 않는다. 다른 AutoConfiguration의 JPA 스캔 범위를 침범하지 않는다.

---

## 코드 진입점

| 구분 | 경로 |
|------|------|
| 도메인 객체 | `services/libs/login/src/main/java/com/econo/auth/login/application/domain/` |
| 입력 포트 (유스케이스) | `services/libs/login/src/main/java/com/econo/auth/login/application/usecase/` |
| 출력 포트 (TokenEncoder/TokenDecoder) | `services/libs/login/src/main/java/com/econo/auth/login/application/repository/` (`TokenEncoder.java`, `TokenDecoder.java`) |
| 서비스 구현체 | `services/libs/login/src/main/java/com/econo/auth/login/application/service/` |
| AutoConfiguration | `services/libs/login/src/main/java/com/econo/auth/login/config/LoginAutoConfiguration.java` |
| 예외 | `services/libs/login/src/main/java/com/econo/auth/login/exception/` |
| 토큰 포트 구현체 (소비자) | `services/apis/auth-api/src/main/java/com/econo/auth/api/config/security/NimbusTokenManager.java` (TokenEncoder·TokenDecoder 둘 다 구현) |

---

## 에러 코드

> 에러 정의: `services/libs/login/src/main/java/com/econo/auth/login/exception/`

| 예외 클래스 | HTTP 매핑 | 에러 코드 | 발생 조건 |
|------------|-----------|-----------|-----------|
| `InvalidTokenException` | 401 (소비자가 처리) | REFRESH_TOKEN_INVALID | `TokenDecoder.decode()` 실패 — 서명 불일치, 만료, 형식 오류 |
| `WrongTokenTypeException` | 401 (소비자가 처리) | REFRESH_TOKEN_INVALID | `token_type`이 `refresh`가 아닐 때 |

> 이 예외들은 lib이 throw하며, 소비자(`auth-api`의 `ReissueController`)가 catch하여 적절한 HTTP 응답으로 변환한다. Spring 타입 의존 없음.

---

## 관련 모듈

| 모듈 | Gradle path | 관계 |
|------|-------------|------|
| member | `:services:libs:member` | `MemberRepository`(회원 조회), `Member` 도메인 객체 사용 |
| service-client | `:services:libs:service-client` | `ClientRedirectUriUseCase`(redirectUri 조회) 사용 |
| auth-api | `:services:apis:auth-api` | 소비자 — `NimbusTokenManager`(`TokenEncoder`/`TokenDecoder` 구현체), `JsonLoginAuthenticationFilter`, `ReissueController`, `GlobalExceptionHandler` |
