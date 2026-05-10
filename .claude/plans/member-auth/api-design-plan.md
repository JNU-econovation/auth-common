# member-auth - api-design

## 메타
- **작업명**: member-auth
- **문서 타입**: api-design
- **작성일**: 2026-05-10
- **관련 문서** (같은 디렉터리):
  - todo.md
  - implementation-plan.md
  - db-design-plan.md

---

## 개요

이 문서는 ECONO 자체 회원 도메인의 가입·로그인·로그아웃 API 3개를 명세한다. 프로토콜은 REST(HTTP/1.1), 직렬화는 JSON, 프레임워크는 Spring Boot 3.2.2(Jakarta Servlet)이다. 로그인 성공 시 JWT를 HttpOnly 쿠키(`auth_token`)에 담아 응답하는 쿠키-전용 토큰 전달 방식을 따르며, API 자체는 인증 없이 공개된다. 게이트웨이가 쿠키의 JWT를 검증하여 `X-User-Passport` 헤더로 변환한 뒤 다운스트림에 전달하므로, 이 세 엔드포인트는 `@PassportAuth`의 적용 대상이 아니다.

---

## 본문

### 엔드포인트 목록

| 메서드 | 경로 | 설명 | 인증 / 권한 | 연관 todo |
|--------|------|------|-------------|-----------|
| POST | `/api/v1/auth/signup` | 이메일/비밀번호 회원 가입 | 불필요 (공개) | API 작업 #1 |
| POST | `/api/v1/auth/login` | 이메일/비밀번호 로그인, JWT 쿠키 발급 | 불필요 (공개) | API 작업 #2 |
| POST | `/api/v1/auth/logout` | JWT 쿠키 만료(클라이언트 세션 종료) | 불필요 (공개) | — (designer 판단 포함) |

---

### 공통 사항

#### 공통 요청 헤더

| 헤더 | 필수 | 설명 |
|------|------|------|
| `Content-Type: application/json` | 필수 (POST 바디 보유 시) | 요청 바디 직렬화 형식 |

> 가입/로그인/로그아웃은 모두 인증 불필요 경로이므로 `Authorization` 헤더나 `X-User-Passport` 헤더는 요구하지 않는다. 게이트웨이 라우팅 설정에서 `/api/v1/auth/**` 경로의 JwtCookieToPassportFilter 인증 게이트는 permit(통과) 처리된다.

#### 공통 에러 응답 형식

프로젝트에 `@RestControllerAdvice`가 처음 도입되는 시점이므로, 아래 구조를 auth-api 모듈의 표준 에러 응답으로 확정한다. 기존 `PassportException`의 `httpStatus` / `errorCode` 필드 설계를 참고하여 일관성을 맞춘다.

```json
{
  "errorCode": "MEMBER_ALREADY_EXISTS",
  "message": "이미 사용 중인 이메일입니다.",
  "timestamp": "2026-05-10T12:00:00"
}
```

> - `errorCode`: 기계가 읽는 상수(UPPER_SNAKE_CASE). 클라이언트 분기에 사용.
> - `message`: 사람이 읽는 설명. 사용자 열거를 유발하는 정보는 포함하지 않는다.
> - `timestamp`: `LocalDateTime` ISO 8601 형식 (`jackson-datatype-jsr310` 적용).

Bean Validation 오류(`MethodArgumentNotValidException`) 시 필드별 오류 목록을 추가로 반환한다.

```json
{
  "errorCode": "VALIDATION_FAILED",
  "message": "요청 값이 올바르지 않습니다.",
  "timestamp": "2026-05-10T12:00:00",
  "fieldErrors": [
    { "field": "email", "message": "이메일 형식이 올바르지 않습니다." },
    { "field": "password", "message": "비밀번호는 8자 이상이어야 합니다." }
  ]
}
```

#### 커스텀 도메인 예외 → HTTP 매핑 (전역 핸들러)

| 예외 클래스 | HTTP 상태 | errorCode |
|-------------|-----------|-----------|
| `MemberAlreadyExistsException` | 409 Conflict | `MEMBER_ALREADY_EXISTS` |
| `InvalidCredentialsException` | 401 Unauthorized | `INVALID_CREDENTIALS` |
| `InvalidPasswordPolicyException` | 400 Bad Request | `INVALID_PASSWORD_POLICY` |
| `MethodArgumentNotValidException` | 400 Bad Request | `VALIDATION_FAILED` |
| 그 외 `Exception` | 500 Internal Server Error | `INTERNAL_SERVER_ERROR` |

> `INTERNAL_SERVER_ERROR` 응답에는 스택트레이스나 내부 예외 메시지를 포함하지 않는다. 서버 로그에만 기록한다.

---

### 엔드포인트 상세

---

#### POST `/api/v1/auth/signup`

- **목적**: 이메일과 비밀번호를 받아 신규 회원을 가입시킨다. 가입 즉시 `ACTIVE` 상태로 활성화된다.
- **연관 todo**: `[ ] POST /api/v1/auth/signup 엔드포인트 추가` (API 작업 #1), `[ ] 전역 예외 핸들러(@RestControllerAdvice) 구성`

- **요청 헤더**:
  ```
  Content-Type: application/json
  ```

- **요청 바디**:
  ```json
  {
    "email": "user@econo.kr",
    "password": "econo1234"
  }
  ```

  | 필드 | 타입 | 제약 |
  |------|------|------|
  | `email` | String | 필수, Jakarta `@Email` 형식, 최대 320자 |
  | `password` | String | 필수, 최소 8자, 영문+숫자 각 1자 이상 포함 |

  > 비밀번호 정책: 최소 8자, 영문자 1자 이상, 숫자 1자 이상. `@Size(min=8)`은 DTO에서, 영문+숫자 조합 검증은 `SignupUseCase` 내 `InvalidPasswordPolicyException`으로 처리.

- **응답 (성공)**:
  - 상태: `201 Created`
  - 바디: 없음 (빈 응답 본문)
  - 헤더: 특별한 응답 헤더 없음

- **응답 (에러)**:
  - `400 Bad Request` `VALIDATION_FAILED` — 이메일 형식 오류 또는 비밀번호 길이 미달 (Bean Validation 실패)
    ```json
    {
      "errorCode": "VALIDATION_FAILED",
      "message": "요청 값이 올바르지 않습니다.",
      "timestamp": "2026-05-10T12:00:00",
      "fieldErrors": [
        { "field": "email", "message": "이메일 형식이 올바르지 않습니다." }
      ]
    }
    ```
  - `400 Bad Request` `INVALID_PASSWORD_POLICY` — 비밀번호 정책 위반 (영문+숫자 미포함 등 UseCase 수준 정책)
    ```json
    {
      "errorCode": "INVALID_PASSWORD_POLICY",
      "message": "비밀번호는 영문자와 숫자를 각 1자 이상 포함해야 합니다.",
      "timestamp": "2026-05-10T12:00:00"
    }
    ```
  - `409 Conflict` `MEMBER_ALREADY_EXISTS` — 이메일 중복
    ```json
    {
      "errorCode": "MEMBER_ALREADY_EXISTS",
      "message": "이미 사용 중인 이메일입니다.",
      "timestamp": "2026-05-10T12:00:00"
    }
    ```

  > 이메일 중복 시 "이 이메일은 이미 가입되어 있습니다"처럼 가입 여부를 단정하는 표현을 쓴다. 이 API는 가입 흐름이므로 중복 사실 자체를 알리는 것이 UX상 필수다. 단, 로그인 API(#2)에서는 이메일 존재 여부를 절대 구분하지 않는다.

- **인증 / 권한**:
  - 필요 여부: **불필요** (공개 엔드포인트)
  - 필요 역할/스코프: 없음
  - 추가 조건: 없음
  - 게이트웨이 인증 게이트: permit(통과)

- **비고**: idempotent하지 않다(같은 이메일로 재요청 시 409). CSRF 토큰 불필요 (쿠키 기반 상태가 아직 없는 시점).

---

#### POST `/api/v1/auth/login`

- **목적**: 이메일과 비밀번호를 검증하고, 성공 시 서명된 JWT를 HttpOnly 쿠키에 담아 반환한다. 사용자 열거(user enumeration)를 방지하기 위해 이메일 미존재와 비밀번호 불일치를 동일한 에러로 응답한다.
- **연관 todo**: `[ ] POST /api/v1/auth/login 엔드포인트 추가` (API 작업 #2)

- **요청 헤더**:
  ```
  Content-Type: application/json
  ```

- **요청 바디**:
  ```json
  {
    "email": "user@econo.kr",
    "password": "econo1234"
  }
  ```

  | 필드 | 타입 | 제약 |
  |------|------|------|
  | `email` | String | 필수, `@NotBlank` |
  | `password` | String | 필수, `@NotBlank` |

  > 로그인 요청은 이메일 형식 검증(`@Email`)을 의도적으로 생략한다. 형식이 틀린 이메일도 "이메일 또는 비밀번호가 올바르지 않습니다"로 응답하여 열거 방지 원칙을 유지한다.

- **응답 (성공)**:
  - 상태: `200 OK`
  - 바디: 없음 (토큰은 쿠키 전용, 바디에 노출 금지)
  - 헤더:
    ```
    Set-Cookie: auth_token=<JWT>; HttpOnly; Secure; SameSite=Strict; Path=/; Max-Age=3600
    ```

  | 쿠키 속성 | 값 | 이유 |
  |-----------|-----|------|
  | 이름 | `auth_token` | `auth.jwt.cookie-name` 설정값과 일치 |
  | `HttpOnly` | 설정됨 | XSS를 통한 JS 접근 차단 |
  | `Secure` | 설정됨 | HTTPS 전송만 허용 (프로덕션 필수) |
  | `SameSite` | `Strict` | CSRF 방지. 동일 사이트 요청만 쿠키 전송 |
  | `Path` | `/` | 모든 경로에서 쿠키 전송 (게이트웨이 전체 경로 커버) |
  | `Max-Age` | `3600` (초) | JWT의 `exp` 클레임과 동일. `auth.jwt.expiry-seconds` 설정값 주입 |

  > `Expires` 속성 대신 `Max-Age`를 사용한다. `Max-Age`는 상대 시간이므로 클라이언트 시계 오차에 덜 민감하다.
  > Spring: `ResponseCookie.from("auth_token", jwt).httpOnly(true).secure(true).sameSite("Strict").path("/").maxAge(expirySeconds).build()`

- **JWT 클레임 계약** (게이트웨이 Passport 변환에 사용):

  ```json
  {
    "sub": "42",
    "email": "user@econo.kr",
    "roles": ["USER"],
    "iat": 1746892800,
    "exp": 1746896400
  }
  ```

  | 클레임 | 타입 | 설명 |
  |--------|------|------|
  | `sub` | String (숫자) | memberId (Long → String 변환) |
  | `email` | String | 회원 이메일 |
  | `roles` | String[] | 역할 목록. 가입 시 기본값 `["USER"]` |
  | `iat` | NumericDate | 발급 시각 (Unix epoch 초) |
  | `exp` | NumericDate | 만료 시각 (Unix epoch 초) |

  > `name` 필드는 이번 member-auth 작업에서 관리하지 않으므로 JWT 클레임에 포함하지 않는다. 게이트웨이가 `Passport` 객체를 생성할 때 `name`은 `null`로 설정한다.
  > 서명 알고리즘: HMAC-SHA256(`HS256`). 비밀키는 환경변수 `JWT_SECRET`에서 주입.

- **응답 (에러)**:
  - `400 Bad Request` `VALIDATION_FAILED` — 이메일 또는 비밀번호 필드가 비어 있음 (`@NotBlank` 위반)
    ```json
    {
      "errorCode": "VALIDATION_FAILED",
      "message": "요청 값이 올바르지 않습니다.",
      "timestamp": "2026-05-10T12:00:00",
      "fieldErrors": [
        { "field": "email", "message": "이메일을 입력해 주세요." }
      ]
    }
    ```
  - `401 Unauthorized` `INVALID_CREDENTIALS` — 이메일 미존재 또는 비밀번호 불일치 (두 경우 동일 응답)
    ```json
    {
      "errorCode": "INVALID_CREDENTIALS",
      "message": "이메일 또는 비밀번호가 올바르지 않습니다.",
      "timestamp": "2026-05-10T12:00:00"
    }
    ```

  > **사용자 열거 방지 원칙**: "이메일이 존재하지 않습니다", "비밀번호가 틀렸습니다" 식의 구분 메시지를 사용하지 않는다. `LoginUseCase`는 이메일 미존재와 비밀번호 불일치 모두 동일한 `InvalidCredentialsException`을 throw한다. 전역 핸들러는 해당 예외를 `INVALID_CREDENTIALS`로 단일 매핑한다.

- **인증 / 권한**:
  - 필요 여부: **불필요** (공개 엔드포인트)
  - 필요 역할/스코프: 없음
  - 추가 조건: 없음
  - 게이트웨이 인증 게이트: permit(통과)

- **비고**: Rate limiting은 이번 작업 스코프 밖이다(OUT OF SCOPE). 브루트포스 방어가 필요하다면 별도 작업으로 추가한다.

---

#### POST `/api/v1/auth/logout`

- **목적**: 클라이언트 브라우저의 `auth_token` 쿠키를 만료시킨다. 서버 측 상태(토큰 블랙리스트 등)는 이번 작업 범위에 없으며 순수 쿠키 만료만 수행한다.
- **연관 todo**: 명시적 todo 항목 없음. todo의 게이트웨이 `JwtCookieToPassportFilter`("쿠키 없음은 통과")와의 일관성을 위해 포함한다. 로그아웃 후 쿠키가 없는 상태에서 공개 경로는 접근 가능해야 하므로 쿠키 만료가 충분하다.

- **요청 헤더**:
  ```
  Content-Type: 불필요 (바디 없음)
  Cookie: auth_token=<JWT>  (있으면 만료, 없어도 정상 처리)
  ```

- **요청 바디**: 없음

- **응답 (성공)**:
  - 상태: `204 No Content`
  - 바디: 없음
  - 헤더 (쿠키 만료):
    ```
    Set-Cookie: auth_token=; HttpOnly; Secure; SameSite=Strict; Path=/; Max-Age=0
    ```

  > `Max-Age=0`은 즉시 만료를 의미한다. `Expires=Thu, 01 Jan 1970 00:00:00 GMT`를 추가로 설정해도 무방하나, `Max-Age=0`만으로 모든 RFC 6265 준수 브라우저에서 쿠키가 삭제된다.

- **응답 (에러)**:
  - 로그아웃은 쿠키가 없는 상태에서도 `204 No Content`를 반환한다(멱등 처리). 별도의 에러 케이스 없음.

- **인증 / 권한**:
  - 필요 여부: **불필요** (공개 엔드포인트)
  - 필요 역할/스코프: 없음
  - 추가 조건: 없음. 이미 로그아웃된 사용자가 재요청해도 동일하게 204를 반환한다.
  - 게이트웨이 인증 게이트: permit(통과)

- **비고**: 서버 측 토큰 무효화(블랙리스트/Redis)는 이번 스코프 밖이다. OIDC 확장 시 RP가 로그아웃을 요청하는 경우(`/logout` 엔드포인트 포함)를 고려해야 하며 그때 재설계한다.

---

### 게이트웨이 라우팅 / 필터 명세 (참고)

> 이 섹션은 `api-gateway` 모듈의 설정에 해당하며, auth-api 엔드포인트 명세의 전제 조건으로 기술한다.

| 경로 | 다운스트림 | 인증 게이트 |
|------|-----------|-------------|
| `/api/v1/auth/signup` | auth-api | permit (필터 통과, Passport 미설정) |
| `/api/v1/auth/login` | auth-api | permit (필터 통과, Passport 미설정) |
| `/api/v1/auth/logout` | auth-api | permit (필터 통과, Passport 미설정) |
| 그 외 `/api/**` | 각 서비스 | JWT 쿠키 검증 → Passport 주입 |

**`JwtCookieToPassportFilter` 동작 요약**:
- `auth_token` 쿠키 존재 → JWT 서명/만료 검증 → 성공 시 `X-User-Passport: <Base64(JSON(Passport))>` 헤더 주입
- 검증 실패(만료, 서명 오류) + 인증 필요 경로 → `401 Unauthorized` 반환
- 검증 실패 + 인증 불필요 경로(permit) → 헤더 미설정 후 통과
- 쿠키 없음과 서명 오류를 구분하여 보안 감사 목적으로 로깅 (`log.warn` vs `log.error`)

**Passport JSON → Base64 변환**:
```
Passport 객체
  → ObjectMapper.writeValueAsString()
  → UTF-8 bytes
  → Base64.getEncoder().encodeToString()
  → X-User-Passport 헤더 값
```

---

## 체크리스트

- [x] todo의 모든 API 작업이 엔드포인트로 명세됨
  - POST /api/v1/auth/signup (API 작업 #1)
  - POST /api/v1/auth/login (API 작업 #2)
  - POST /api/v1/auth/logout (designer 판단 포함)
  - 게이트웨이 라우팅/필터 명세 포함 (API 작업 #3, #4)
- [x] 각 엔드포인트의 인증/권한이 명시됨 (기본값 의존 X) — 3개 모두 "인증 불필요, permit" 명시
- [x] 모든 에러 케이스가 기존 에러 체계로 매핑됨 — `PassportException` 패턴 참조하여 도메인 예외 4종 정의
- [x] 요청·응답 스키마가 실제 본문 예시로 작성됨 — JSON 예시 포함
- [x] 프로젝트 표준 헤더/메타데이터가 누락 없이 명시됨 — `Set-Cookie` 속성 전체 명시, `X-User-Passport` 변환 방식 기술

---

## 참고

- `/Users/kimjongmin/Library/Mobile Documents/com~apple~CloudDocs/대학/에코노베이션/Project/auth-common/docs/ARCHITECTURE.md` — 모듈 의존성, 인증 흐름, 에러 코드 체계
- `/Users/kimjongmin/Library/Mobile Documents/com~apple~CloudDocs/대학/에코노베이션/Project/auth-common/docs/CONVENTION.md` — 네이밍, 예외 처리, Lombok, JSON 직렬화 컨벤션
- `/Users/kimjongmin/Library/Mobile Documents/com~apple~CloudDocs/대학/에코노베이션/Project/auth-common/services/libs/auth-common-lib/src/main/java/com/econo/common/auth/core/passport/Passport.java` — Passport 필드 구조(`memberId`, `email`, `name`, `roles`, `issuedAt`, `expiresAt`)
- `/Users/kimjongmin/Library/Mobile Documents/com~apple~CloudDocs/대학/에코노베이션/Project/auth-common/services/libs/auth-common-lib/src/main/java/com/econo/common/auth/core/passport/PassportException.java` — 에러 코드 상수 및 정적 팩토리 패턴 참조
- `/Users/kimjongmin/Library/Mobile Documents/com~apple~CloudDocs/대학/에코노베이션/Project/auth-common/services/libs/auth-common-lib/src/main/java/com/econo/common/auth/web/resolver/PassportArgumentResolver.java` — `X-User-Passport` 헤더 소비 방식(Base64 디코딩 → JSON 역직렬화)
- RFC 6265 — HTTP State Management Mechanism (쿠키 `Max-Age` 규격)
- RFC 7519 — JSON Web Token (JWT 클레임 표준)
