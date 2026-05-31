# api-gateway

클라이언트 요청을 수신해 인증 처리 후 다운스트림 마이크로서비스로 라우팅하는 게이트웨이. Spring Cloud Gateway(WebFlux) 기반.

## 기본 정보

| 항목 | 값 |
|---|---|
| 역할 | API Gateway (Spring Cloud Gateway 기반, WebFlux) |
| 대상 사용자 | 외부 클라이언트 (브라우저, 모바일) |
| 인증 방식 | `Authorization: Bearer <SAS JWT>` 헤더 검증 (RS256 JWKS) → `Passport` 생성 → JSON 직렬화 후 Base64 인코딩 → `X-User-Passport` 헤더로 다운스트림 전달 |
| 진입점 | `src/main/java/com/econo/auth/gateway/ApiGatewayApplication.java` |

## 주요 기능

| 기능 | 설명 |
|---|---|
| JWT 검증 필터 | `Authorization: Bearer` 헤더의 SAS 발급 JWT를 `JwtVerifier`(JWKS URI 기반 ReactiveJwtDecoder)로 RS256 검증 |
| Passport 발급 | JWT 클레임(`memberId`, `loginId`, `name`, `generation`, `status`, `roles`)을 `Passport`로 변환·직렬화 후 `X-User-Passport` 헤더 주입 |
| 라우팅 설정 | `/api/v1/auth/**` → `auth-api` 서버. SAS 표준 엔드포인트(`/oauth2/**`, `/.well-known/**`, `/userinfo`)도 `auth-api`로 프록시 |

## 주요 엔드포인트

라우팅 게이트웨이이므로 자체 엔드포인트는 없다. 라우팅 매핑:

| 경로 패턴 | 대상 서버 | 인증 필요 |
|---|---|---|
| `/api/v1/auth/signup` | `auth-api` | 불필요 (permit) |
| `/api/v1/auth/login` | `auth-api` | 불필요 (permit) — 세션 수립 엔드포인트 |
| `/api/v1/auth/logout` | `auth-api` | 불필요 (permit) |
| `/oauth2/**` | `auth-api` | 불필요 (permit) — SAS 표준 OAuth2 엔드포인트 |
| `/.well-known/**` | `auth-api` | 불필요 (permit) — OIDC Discovery |
| `/userinfo` | `auth-api` | 불필요 (permit) — OIDC UserInfo |
| 그 외 경로 | (다운스트림 서비스 미설정) | 필요 (Bearer 토큰 없으면 401) |

## 인증 및 권한 검증

- 본 앱은 **인증을 검증·변환**한다 (발급은 `auth-api`/SAS의 역할).
- **⚠️ AUTH_JWKS_URI**: `GatewaySecurityConfig`가 참조하는 `AUTH_JWKS_URI` 환경변수는 auth-api **내부** 주소(`http://auth-api:8081/oauth2/jwks`)를 가리켜야 한다. Gateway 공개 URL을 사용하면 자기참조 루프가 발생한다.
- **⚠️ issuer 검증**: `NimbusReactiveJwtDecoder`는 JWT의 `iss` 클레임을 `AUTH_ISSUER_URI`(Gateway 공개 URL)로 검증한다. JWKS fetch 호스트(내부 auth-api)와 issuer 클레임 호스트(Gateway 공개 URL)가 달라도 무관하다.
- Bearer 토큰 없는 요청이 보호 경로에 도달하면 401 반환, permit 경로는 헤더 미설정 후 통과.
- 만료·서명 오류 JWT는 보호 경로에서 401, permit 경로에서는 통과.

> 인증 흐름 전체: [`docs/ARCHITECTURE.md` 인증 흐름](../../../docs/ARCHITECTURE.md#인증-흐름)

## 횡단 관심사

- **BearerToPassportFilter** (`GlobalFilter`): 모든 요청에 적용. `Authorization: Bearer` 헤더 추출 → JWT 검증 → Passport 직렬화 → 헤더 주입 순서로 동작.

## 모듈 구조

```
services/apis/api-gateway/
└── src/main/java/com/econo/auth/gateway/
    ├── ApiGatewayApplication.java        # main() 진입점
    ├── filter/
    │   └── BearerToPassportFilter.java   # GlobalFilter 구현
    ├── security/
    │   ├── JwtVerifier.java              # JWKS URI 기반 RS256 JWT 검증
    │   └── PassportBuilder.java          # SAS JWT 클레임 → Passport 생성·직렬화
    └── config/
        ├── GatewayRoutingConfig.java     # 라우트 및 permit 경로 설정
        └── GatewaySecurityConfig.java    # JwtVerifier 빈 등록 (AUTH_JWKS_URI 주입)
```

## 관련 모듈

- `passport` — `Passport` 직렬화·역할 상수 참조
