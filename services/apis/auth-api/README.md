# auth-api

ECONO OIDC Authorization Server. Spring Authorization Server(SAS 1.x) 기반으로 토큰 발급 권위를 일원화한 헤드리스 IdP.

## 기본 정보

| 항목 | 값 |
|---|---|
| 역할 | OIDC Authorization Server (SAS 1.x) + 회원 가입·로그아웃 API |
| 대상 사용자 | API Gateway (외부 클라이언트는 Gateway를 통해 접근) |
| 인증 방식 | JSON 로그인(`/api/v1/auth/login`) → 서버 세션 수립 → SAS Authorization Code + PKCE → Access/ID Token 발급 |
| 진입점 | `src/main/java/com/econo/auth/api/AuthApiApplication.java` |

## 주요 기능

| 도메인 | 설명 |
|---|---|
| member | loginId·비밀번호 회원 가입 / 세션 기반 로그인 / 로그아웃 |
| OIDC Authorization Server | SAS 표준 엔드포인트 — Authorization Code, Token, JWKS, UserInfo, Discovery |

## 주요 엔드포인트

### 회원 API

| Controller | 메서드·경로 | 성공 응답 |
|---|---|---|
| MemberController | `POST /api/v1/auth/signup` | 201 Created |
| JsonLoginAuthenticationFilter | `POST /api/v1/auth/login` | 200 OK + `Set-Cookie: SESSION=...` |
| MemberController | `POST /api/v1/auth/logout` | 200 OK (SESSION 쿠키 만료) |

### SAS 표준 엔드포인트 (자동 활성화)

| 경로 | 설명 |
|---|---|
| `GET /oauth2/authorize` | Authorization Code 발급 (PKCE 필수) |
| `POST /oauth2/token` | Access Token / ID Token / Refresh Token 교환 |
| `GET /oauth2/jwks` | RSA 공개키 JWKS |
| `GET /userinfo` | OIDC UserInfo |
| `GET /.well-known/openid-configuration` | OIDC Discovery Document |

## 인증 및 권한 검증

- 본 앱은 **인증을 발급**한다 (검증·소비는 다운스트림 서비스의 역할).
- 필터체인은 두 개가 등록된다.
  - `@Order(1)` SAS 필터체인(`AuthorizationServerConfig`): `/oauth2/**`, `/.well-known/**`, `/userinfo` 처리.
  - `@Order(2)` 앱 필터체인(`SecurityConfig`): `/api/v1/auth/**` 처리. 세션 기반(`IF_REQUIRED`). CSRF는 `CookieCsrfTokenRepository.withHttpOnlyFalse()`, 단 `/api/v1/auth/login`, `/api/v1/auth/signup`, `/api/v1/auth/logout` 제외.
- **⚠️ AUTH_ISSUER_URI**: issuer는 auth-api 내부 URL이 아닌 **Gateway 공개 URL**이어야 한다. JWKS URI·Discovery document 등 모든 엔드포인트 URL이 Gateway 도메인으로 발행된다.
- **⚠️ RSA 키 고정 kid**: `jwkSource()` 빈은 `keyID("econo-auth-rsa-key-v1")` 고정 kid를 사용한다. kid가 바뀌면 기발급 토큰의 JWKS 키 매칭이 영구 실패한다.
- `@PassportAuth`는 향후 인증이 필요한 엔드포인트가 도입될 때 사용한다.

## 횡단 관심사

- **GlobalExceptionHandler** (`@RestControllerAdvice`): Bean Validation 오류, `MemberAlreadyExistsException`, `InvalidPasswordPolicyException`, `IllegalArgumentException`, `ResponseStatusException`, 그 외 예외를 에러 코드·메시지 형태로 변환. Spring Security 인증 예외(`AuthenticationException`)는 `JsonLoginAuthenticationFilter`가 직접 처리하므로 핸들러 미포함.

> 에러 코드 상세: `src/main/java/com/econo/auth/api/exception/GlobalExceptionHandler.java`

## 모듈 구조

```
services/apis/auth-api/
└── src/main/java/com/econo/auth/api/
    ├── AuthApiApplication.java             # main() 진입점
    ├── adapter/in/web/
    │   ├── MemberController.java           # signup / logout 핸들러
    │   └── SignupRequest.java              # 가입 요청 DTO (record)
    ├── filter/
    │   └── JsonLoginAuthenticationFilter.java  # POST /api/v1/auth/login JSON 인증 → 세션 수립
    ├── security/
    │   ├── MemberUserDetailsService.java   # UserDetailsService — loginId로 Member 로드
    │   └── MemberUserDetails.java          # UserDetails 확장 — Member 래퍼
    ├── config/
    │   ├── AuthorizationServerConfig.java  # SAS @Order(1) 필터체인, AuthorizationServerSettings
    │   ├── SecurityConfig.java             # 앱 @Order(2) 필터체인 (세션 기반, CSRF)
    │   ├── RegisteredClientConfig.java     # JdbcRegisteredClientRepository + 1st-party client seed
    │   ├── OAuth2AuthorizationServiceConfig.java  # JdbcOAuth2AuthorizationService/ConsentService 빈
    │   ├── RsaKeyConfig.java               # PEM 환경변수 → JWKSource, JwtEncoder 빈
    │   ├── PassportTokenCustomizer.java    # OAuth2TokenCustomizer — Passport 클레임 주입
    │   └── ApplicationServiceConfig.java  # SignupService 빈 등록 (LoginService 제거됨)
    └── exception/
        └── GlobalExceptionHandler.java    # 전역 예외 핸들러
```

## 관련 모듈

- `member-core` — 도메인 모델·비즈니스 로직·유스케이스 (SignupService)
- `member-infra` — JPA Repository, BCrypt, Flyway 마이그레이션 (SAS 스키마 포함)
- `passport` — Passport 도메인 (전이 의존)
