# auth-api

ECONO 인증 API 서버. 회원 가입·로그인·로그아웃 인증 도메인의 HTTP 진입점.

## 기본 정보

| 항목 | 값 |
|---|---|
| 역할 | ECONO 자체 회원 인증 API 서버 |
| 대상 사용자 | API Gateway (외부 클라이언트는 직접 호출하지 않음) |
| 인증 방식 | loginId·비밀번호 → JWT를 HttpOnly 쿠키(`auth_token`)로 응답 → Gateway가 Passport로 변환 |
| 진입점 | `src/main/java/com/econo/auth/api/AuthApiApplication.java` |

## 주요 기능

| 도메인 | 설명 |
|---|---|
| member | loginId·비밀번호 회원 가입 / 로그인 / 로그아웃 |

## 주요 엔드포인트

| Controller | 메서드·경로 | 성공 응답 |
|---|---|---|
| MemberController | `POST /api/v1/auth/signup` | 201 Created |
| MemberController | `POST /api/v1/auth/login` | 200 OK + `Set-Cookie: auth_token` |
| MemberController | `POST /api/v1/auth/logout` | 200 OK (쿠키 Max-Age=0 만료) |

## 인증 및 권한 검증

- 본 앱은 **인증을 발급**한다 (검증·소비는 다운스트림 서비스의 역할).
- `SecurityConfig`에서 모든 경로 `permitAll` + CSRF 비활성화 + stateless 세션 설정.
- `@PassportAuth`는 향후 인증이 필요한 엔드포인트가 도입될 때 사용한다.

## 횡단 관심사

- **GlobalExceptionHandler** (`@RestControllerAdvice`): Bean Validation 오류, 도메인 예외(`MemberAlreadyExistsException`, `InvalidCredentialsException`, `InvalidPasswordPolicyException`), `IllegalArgumentException`, 그 외 예외를 에러 코드·메시지 형태로 변환.

> 에러 코드 상세: `src/main/java/com/econo/auth/api/exception/GlobalExceptionHandler.java`

## 모듈 구조

```
services/apis/auth-api/
└── src/main/java/com/econo/auth/api/
    ├── AuthApiApplication.java          # main() 진입점
    ├── adapter/in/web/
    │   ├── MemberController.java        # signup / login / logout 핸들러
    │   ├── SignupRequest.java           # 가입 요청 DTO (record)
    │   └── LoginRequest.java           # 로그인 요청 DTO (record)
    ├── config/
    │   ├── ApplicationServiceConfig.java  # SignupService / LoginService 빈 등록
    │   ├── SecurityConfig.java            # CSRF 비활성화, stateless, permitAll
    │   └── JwtCookieProperties.java       # JWT 쿠키 설정 바인딩
    └── exception/
        └── GlobalExceptionHandler.java    # 전역 예외 핸들러
```

## 관련 모듈

- `auth-core` — 도메인 모델·비즈니스 로직·유스케이스
- `auth-infra` — JPA Repository, BCrypt, JWT 발급 어댑터
- `auth-common-lib` — Passport 도메인 (전이 의존)

> 헥사고날 어댑터 구조에서 도메인 패키지가 없는 경우, 앱 요약 README에 도메인별 엔드포인트·DTO 정보를 포함한다.
