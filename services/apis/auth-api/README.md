# auth-api

ECONO 인증 API 서버. 회원 가입·로그인 등 인증 도메인의 HTTP 진입점.

> **상태:** 골격 단계 — `AuthApiApplication.java`(`main()` 진입점)만 존재. 도메인 컨트롤러·서비스는 `member-auth` 작업에서 도입된다.

## 기본 정보

| 항목 | 값 |
|---|---|
| 역할 | ECONO 자체 회원 인증 API 서버 |
| 대상 사용자 | API Gateway (직접 호출되지 않음) |
| 인증 방식 | (도입 예정) 이메일·비밀번호 → JWT를 HttpOnly 쿠키로 응답 → Gateway가 Passport로 변환 |
| 진입점 | `src/main/java/com/econo/auth/api/AuthApiApplication.java` |

## 주요 기능

현재 미구현. `member-auth` 작업의 `api-design-plan.md` 참조.

| 도메인 | 설명 |
|---|---|
| (도입 예정) member | 이메일·비밀번호 회원 가입 / 로그인 / 로그아웃 |

## 주요 엔드포인트

| Controller | 경로 | 비고 |
|---|---|---|
| (도입 예정) | `POST /api/v1/auth/signup` | 회원 가입 |
| (도입 예정) | `POST /api/v1/auth/login` | 로그인, HttpOnly 쿠키로 JWT 발급 |
| (도입 예정) | `POST /api/v1/auth/logout` | 로그아웃, 쿠키 만료 |

## 인증 및 권한 검증

- 본 앱은 **인증을 발급**한다 (검증·소비는 다운스트림 서비스의 역할).
- `/api/v1/auth/**` 엔드포인트는 모두 인증 불필요(`permit`).
- `auth-common-lib`의 `@PassportAuth`는 향후 인증이 필요한 엔드포인트가 도입될 때 사용한다.

## 횡단 관심사

(현재 없음. 도입 예정 — `GlobalExceptionHandler`, 로깅, 입력 검증 등.)

## 모듈 구조

```
services/apis/auth-api/
└── src/main/java/com/econo/auth/api/
    └── AuthApiApplication.java          # main() 진입점
```

## 관련 모듈

- `auth-core` — 도메인 모델·비즈니스 로직
- `auth-infra` — JPA Repository, BCrypt, JWT 발급
- `auth-common-lib` — Passport 도메인 (전이 의존)
