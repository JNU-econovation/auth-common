# api-gateway

클라이언트 요청을 수신해 인증 처리 후 다운스트림 마이크로서비스로 라우팅하는 게이트웨이. Spring Cloud Gateway(WebFlux) 기반.

> **상태:** 골격 단계 — `ApiGatewayApplication.java`(`main()` 진입점)만 존재. JWT 검증·Passport 발급·라우팅 필터는 `member-auth` 작업에서 도입된다.

## 기본 정보

| 항목 | 값 |
|---|---|
| 역할 | API Gateway (Spring Cloud Gateway 기반, WebFlux) |
| 대상 사용자 | 외부 클라이언트 (브라우저, 모바일) |
| 인증 방식 | (도입 예정) 쿠키 JWT 검증 → `Passport` 생성 → JSON 직렬화 후 Base64 인코딩 → `X-User-Passport` 헤더로 다운스트림 전달 |
| 진입점 | `src/main/java/com/econo/auth/gateway/ApiGatewayApplication.java` |

## 주요 기능

현재 미구현. `member-auth` 작업의 `implementation-plan.md` 참조.

| 기능 | 설명 |
|---|---|
| (도입 예정) JWT 검증 필터 | 쿠키의 JWT를 검증하고 클레임을 추출 |
| (도입 예정) Passport 발급 | 클레임을 `Passport`로 변환·직렬화 후 헤더 주입 |
| (도입 예정) 라우팅 설정 | `/api/v1/auth/**` → `auth-api`, 그 외 다운스트림 매핑 |

## 주요 엔드포인트

라우팅 게이트웨이이므로 자체 엔드포인트는 없다. 라우팅 매핑은 `application.yml` 또는 `RouteLocator` 설정으로 정의한다.

## 인증 및 권한 검증

- 본 앱은 **인증을 검증·변환**한다 (발급은 `auth-api`의 역할).
- 쿠키의 JWT를 검증해 `Passport` 객체를 만들고, JSON 직렬화 + Base64 인코딩 후 `X-User-Passport` 헤더로 다운스트림에 전달한다.
- 다운스트림 서비스는 `auth-common-lib`의 `@PassportAuth`로 자동 주입받는다.
- 인증 발급 경로(`/api/v1/auth/**`)는 JWT 없이도 통과(`permit`)한다.

> 인증 흐름 전체: [`docs/ARCHITECTURE.md` 인증 흐름](../../../docs/ARCHITECTURE.md#인증-흐름)

## 횡단 관심사

(현재 없음. 도입 예정 — 라우팅 설정, JWT 검증 필터, 글로벌 에러 응답.)

## 모듈 구조

```
services/apis/api-gateway/
└── src/main/java/com/econo/auth/gateway/
    └── ApiGatewayApplication.java       # main() 진입점
```

## 관련 모듈

- `auth-common-lib` — `Passport` 직렬화·역할 상수 참조
