# api-gateway

클라이언트 요청을 수신해 인증 처리 후 다운스트림 마이크로서비스로 라우팅하는 게이트웨이. Spring Cloud Gateway(WebFlux) 기반.

## 기본 정보

| 항목 | 값 |
|---|---|
| 역할 | API Gateway (Spring Cloud Gateway 기반, WebFlux) |
| 대상 사용자 | 외부 클라이언트 (브라우저, 모바일) |
| 인증 방식 | `auth_token` 쿠키의 JWT 검증 → `Passport` 생성 → JSON 직렬화 후 Base64 인코딩 → `X-User-Passport` 헤더로 다운스트림 전달 |
| 진입점 | `src/main/java/com/econo/auth/gateway/ApiGatewayApplication.java` |

## 주요 기능

| 기능 | 설명 |
|---|---|
| JWT 검증 필터 | `auth_token` 쿠키의 JWT 서명·만료를 `JwtVerifier`로 검증 |
| Passport 발급 | JWT 클레임(`memberId`, `loginId`, `name`, `generation`, `status`, `roles`)을 `Passport`로 변환·직렬화 후 `X-User-Passport` 헤더 주입 |
| 라우팅 설정 | `/api/v1/auth/**` → `auth-api` 서버 (`AUTH_API_URI` 환경변수) |

## 주요 엔드포인트

라우팅 게이트웨이이므로 자체 엔드포인트는 없다. 라우팅 매핑:

| 경로 패턴 | 대상 서버 | 인증 필요 |
|---|---|---|
| `/api/v1/auth/signup` | `auth-api` | 불필요 (permit) |
| `/api/v1/auth/login` | `auth-api` | 불필요 (permit) |
| `/api/v1/auth/logout` | `auth-api` | 불필요 (permit) |
| 그 외 경로 | (다운스트림 서비스 미설정) | 필요 (쿠키 없으면 401) |

## 인증 및 권한 검증

- 본 앱은 **인증을 검증·변환**한다 (발급은 `auth-api`의 역할).
- **⚠️ JWT_SECRET**: `JwtVerifier`가 참조하는 `JWT_SECRET` 환경변수는 `auth-infra`의 `JwtTokenIssuerAdapter`와 동일한 값이어야 한다. 불일치 시 모든 JWT 검증이 실패한다.
- JWT 없는 요청이 보호 경로에 도달하면 401 반환, permit 경로는 헤더 미설정 후 통과.
- 만료된 JWT는 보호 경로에서 401, permit 경로에서는 통과.

> 인증 흐름 전체: [`docs/ARCHITECTURE.md` 인증 흐름](../../../docs/ARCHITECTURE.md#인증-흐름)

## 횡단 관심사

- **JwtCookieToPassportFilter** (`GlobalFilter`): 모든 요청에 적용. 쿠키 추출 → JWT 검증 → Passport 직렬화 → 헤더 주입 순서로 동작.

## 모듈 구조

```
services/apis/api-gateway/
└── src/main/java/com/econo/auth/gateway/
    ├── ApiGatewayApplication.java        # main() 진입점
    ├── filter/
    │   └── JwtCookieToPassportFilter.java  # GlobalFilter 구현
    ├── security/
    │   ├── JwtVerifier.java              # JWT 서명·만료 검증
    │   └── PassportBuilder.java          # 클레임 → Passport 생성·직렬화
    └── config/
        └── GatewayRoutingConfig.java     # 라우트 및 permit 경로 설정
```

## 관련 모듈

- `auth-common-lib` — `Passport` 직렬화·역할 상수 참조
