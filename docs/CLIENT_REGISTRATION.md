# OAuth 클라이언트 등록 가이드

클라이언트 등록 경로는 두 가지다.

| 경로 | 엔드포인트 | 인증 | 대상 |
|------|-----------|------|------|
| **셀프 등록** | `POST /api/v1/clients` | X-User-Passport (Gateway 주입) — memberId 필수 | 인증된 에코노 회원 누구나 |
| **어드민 등록** | `POST /api/v1/admin/clients` | X-User-Passport ADMIN role 필수 | 관리자 |

두 경로 모두 **authorization_code + PKCE** 클라이언트로 등록된다.

> 설계 근거: [ADR-0013](./adr/0013-passport-member-self-registration.md)

---

## 셀프 등록 (`POST /api/v1/clients`)

인증된 에코노 회원이 자신의 서비스 앱을 SSO 클라이언트로 직접 등록한다. ADMIN 역할 없이도 가능하나, Gateway가 주입하는 `X-User-Passport` 헤더에서 `memberId`를 추출할 수 없으면 401을 반환한다.

### 비즈니스 규칙

- **1인 최대 5개** 제한. 초과 시 422 `CLIENT_LIMIT_EXCEEDED`.
- `redirectUris` 는 필수이며 비어 있으면 400 `REDIRECT_URI_REQUIRED`.
- `clientSecret`은 **등록 응답에서 단 1회만 평문으로 노출**된다. 분실 시 재등록 필요.
- `clientSecret`은 `service_client.client_secret_hash`에 BCrypt(cost=12) 해시로 저장된다. SAS `oauth2_registered_client`에는 저장하지 않는다(B안). 현재 `clientSecret`을 소비하는 in-scope 엔드포인트는 없다 — 향후 redirect-uri 셀프관리 등에서 사용 예정인 선발급·보관 성격.
- `ownerId`(소유자 회원 ID)가 `service_client.owner_id`에 저장된다.

### 요청

```bash
curl -X POST http://auth-api:8081/api/v1/clients \
  -H "Content-Type: application/json" \
  -H "X-User-Passport: <Gateway-주입-헤더>" \
  -d '{
    "clientName": "EEOS 웹앱",
    "redirectUris": ["https://app.econovation.kr/callback"]
  }'
```

| 필드 | 타입 | 필수 여부 | 설명 |
|------|------|-----------|------|
| `clientName` | String | 필수 | 빈 문자열 불가 |
| `redirectUris` | Set\<String\> | 필수 | 비어 있으면 400 |

> `X-User-Passport` 헤더는 Gateway가 자동 주입한다. 직접 전달하는 경우는 통합 테스트에서만 사용한다.

### 응답

```json
{
  "clientId": "550e8400-e29b-41d4-a716-446655440000",
  "clientSecret": "a3f7c2d1-... (1회만 표시)"
}
```

- `clientSecret`은 이 응답에서만 노출된다.

---

## 어드민 등록 (`POST /api/v1/admin/clients`)

ADMIN 또는 SUPER_ADMIN 역할이 있는 회원이 등록한다. `owner_id`와 `client_secret_hash`는 null로 저장된다.

> 어드민 등록 세부 사항 및 redirectUri 관리(조회·추가·삭제·전체교체) 4개 엔드포인트는 AdminClientController에 위임. 경로: `services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/in/web/AdminClientController.java`

---

## 에러 코드

### 셀프 등록 (`POST /api/v1/clients`)

| HTTP | 코드 | 발생 조건 |
|------|------|-----------|
| 400 | `AUTH_BAD_REQUEST` | `X-User-Passport` 헤더 Base64/JSON 파싱 불가 (econo-passport badRequest) |
| 400 | `REDIRECT_URI_REQUIRED` | `redirectUris`가 null이거나 비어있음 |
| 400 | `VALIDATION_FAILED` | `clientName`이 빈 문자열 (`@NotBlank` Bean Validation, 웹 레이어) |
| 400 | `INVALID_ARGUMENT` | `clientName`이 null 또는 blank (`RegisterOAuthClientService.selfRegister` 서비스 계층 방어선, 웹 레이어 우회 시) |
| 401 | `AUTH_UNAUTHORIZED` | `X-User-Passport` 헤더 누락, 또는 `memberId` 없음 등 invalid passport (econo-passport unauthorized) |
| 409 | `DUPLICATE_CLIENT_NAME` | `clientName` 중복 |
| 422 | `CLIENT_LIMIT_EXCEEDED` | 해당 회원의 등록 클라이언트가 이미 5개 |

> Passport 파싱·검증은 econo-passport 라이브러리(`@PassportAuth`, `PassportArgumentResolver`)가 담당한다. `GlobalExceptionHandler`가 `PassportException`을 수신하여 401 → `AUTH_UNAUTHORIZED`, 그 외 HTTP 상태 → 해당 에러 코드로 매핑한다.

### 어드민 등록 및 관리 (`/api/v1/admin/clients/**`)

| HTTP | 코드 | 발생 조건 |
|------|------|-----------|
| 400 | `AUTH_BAD_REQUEST` | `X-User-Passport` 헤더 Base64/JSON 파싱 불가 (econo-passport badRequest) |
| 401 | `AUTH_UNAUTHORIZED` | `X-User-Passport` 헤더 누락 또는 invalid passport (econo-passport unauthorized) |
| 403 | `FORBIDDEN` | ADMIN/SUPER_ADMIN 역할 부족 (econo-passport forbidden) |
| 400 | `REDIRECT_URI_REQUIRED` | `redirectUris`가 null이거나 비어있음 |
| 400 | `VALIDATION_FAILED` | `clientName`이 빈 문자열 (`@NotBlank` Bean Validation) |
| 409 | `DUPLICATE_CLIENT_NAME` | `clientName` 중복 |

> 에러 응답 형식: `{"errorCode": "...", "message": "...", "timestamp": "..."}`

---

## CORS 자동 허용

`redirectUri`에 등록된 오리진은 `DynamicCorsConfigurationSource`에 의해 자동으로 CORS 허용 목록에 추가된다.
별도 CORS 환경변수 설정 불필요.

예: `https://app.econovation.kr/callback` 등록 → `https://app.econovation.kr` 자동 허용
