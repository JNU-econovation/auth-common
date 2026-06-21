# route-self-registration - api-design

## 메타
- **작업명**: route-self-registration
- **문서 타입**: api-design
- **작성일**: 2026-06-21 (전면 재작성 — 별도 `/api/v1/routes` CRUD 5개 폐기, `POST /api/v1/clients` 흡수 설계로 대체)
- **관련 문서** (같은 디렉터리):
  - todo.md
  - implementation-plan.md
  - db-design-plan.md

## 개요

기존 설계(별도 `/api/v1/routes` CRUD 5개 엔드포인트)를 전면 폐기하고, **기존 회원 셀프 클라이언트 등록 API `POST /api/v1/clients`에 라우트 등록을 흡수**한다. 요청 바디에 `pathPrefix` + `upstreamUrl`을 선택 필드로 추가하며, 두 필드가 모두 존재하면 같은 트랜잭션에서 `service_route` 1건을 생성하고 게이트웨이를 즉시 갱신한다. 라우트 관련 셀프 조회/수정/삭제 엔드포인트는 이번 범위 밖이다. 어드민 라우트 API(`/api/v1/admin/routes`)는 변경 없이 유지한다. 프로토콜은 REST/JSON, Spring MVC 컨벤션을 따른다.

---

## 본문

### 엔드포인트 목록

| 메서드 / 작업 | 경로 / 식별자 | 설명 | 인증 / 권한 | 연관 todo |
|---------------|---------------|------|-------------|-----------|
| POST | `/api/v1/clients` | 셀프 클라이언트 등록 (라우트 선택 생성 흡수) | `@PassportAuth` (역할 제약 없음, memberId 필수) | API 작업 #1 |
| POST | `/api/v1/admin/routes` | 어드민 라우트 등록 (변경 없음) | `@PassportAuth` (ADMIN / SUPER_ADMIN 역할) | API 작업 #2 — 유지 |
| GET | `/api/v1/admin/routes` | 어드민 라우트 목록 (변경 없음) | `@PassportAuth` (ADMIN / SUPER_ADMIN 역할) | API 작업 #2 — 유지 |
| GET | `/api/v1/admin/routes/{routeId}` | 어드민 라우트 단건 (변경 없음) | `@PassportAuth` (ADMIN / SUPER_ADMIN 역할) | API 작업 #2 — 유지 |
| PUT | `/api/v1/admin/routes/{routeId}` | 어드민 라우트 수정 (변경 없음) | `@PassportAuth` (ADMIN / SUPER_ADMIN 역할) | API 작업 #2 — 유지 |
| DELETE | `/api/v1/admin/routes/{routeId}` | 어드민 라우트 삭제 (변경 없음) | `@PassportAuth` (ADMIN / SUPER_ADMIN 역할) | API 작업 #2 — 유지 |

> 이 설계 문서는 `POST /api/v1/clients` 변경 사항에만 집중한다. 어드민 라우트 5개 엔드포인트는 기존 `AdminRouteController` / `AdminRouteApiDocs`를 유지하며 별도 명세가 필요하지 않다.

---

### 공통 사항

#### 공통 요청 헤더

| 헤더 | 필수 | 설명 |
|------|------|------|
| `X-User-Passport` | 필수 | Gateway가 Bearer JWT → Passport 변환 후 주입. Base64 인코딩 JSON. 클라이언트가 직접 설정하지 않는다 (위조 시 Gateway가 항상 제거·재주입). |
| `Content-Type` | 필수 | `application/json` |

> 클라이언트는 `Authorization: Bearer <access_token>` 헤더(또는 `Cookie: at=<token>`)를 Gateway에 전달한다. Gateway가 RS256 검증 후 `X-User-Passport`를 주입하므로, auth-api 엔드포인트 수준에서는 `X-User-Passport`만 처리한다.

#### 공통 에러 응답 형식

`GlobalExceptionHandler.ApiError` 레코드:

```json
{
  "errorCode": "ROUTE_NAMESPACE_INVALID",
  "message": "pathPrefix는 /api/{namespace} 형태여야 합니다.",
  "timestamp": "2026-06-21T10:00:00",
  "fieldErrors": null
}
```

`VALIDATION_FAILED`의 경우 `fieldErrors`가 채워진다:

```json
{
  "errorCode": "VALIDATION_FAILED",
  "message": "요청 값이 올바르지 않습니다.",
  "timestamp": "2026-06-21T10:00:00",
  "fieldErrors": [
    { "field": "routeFields", "message": "pathPrefix와 upstreamUrl은 함께 제공되어야 합니다." }
  ]
}
```

---

### 엔드포인트 상세

#### POST /api/v1/clients (변경)

- **목적**: 인증된 에코노 회원이 SSO 클라이언트를 등록한다. 이번 변경으로 요청 바디에 `pathPrefix` + `upstreamUrl`을 선택적으로 추가할 수 있으며, 두 필드가 모두 존재하면 클라이언트와 `service_route` 1건을 같은 트랜잭션에서 원자적으로 생성하고 게이트웨이를 즉시 갱신한다. 두 필드가 모두 없으면 기존과 동일하게 클라이언트만 생성한다. 1 클라이언트 = 최대 1 라우트.

- **연관 todo**: `[ ] POST /api/v1/clients 요청/응답 스펙 확장 — 요청 바디에 선택 필드 추가: pathPrefix, upstreamUrl / 응답 바디에 라우트 필드 추가: routeId, pathPrefix, upstreamUrl, enabled`

- **요청 헤더**:
  - `X-User-Passport`: Gateway 주입 (필수)
  - `Content-Type: application/json`

- **요청 바디 / 파라미터**:

  ```json
  {
    "clientName": "에코노 SPA",
    "redirectUris": ["http://localhost:3000/callback"],
    "pathPrefix": "/api/eeos",
    "upstreamUrl": "http://eeos-service:8080"
  }
  ```

  | 필드 | 타입 | 필수 여부 | 검증 규칙 |
  |------|------|-----------|-----------|
  | `clientName` | String | 필수 (`@NotBlank`) | 기존과 동일 |
  | `redirectUris` | Set\<String\> | 필수 (`@NotNull`) | 기존과 동일 (비어있으면 `REDIRECT_URI_REQUIRED`) |
  | `pathPrefix` | String | 선택 (nullable) | `upstreamUrl`과 반드시 쌍으로 존재해야 함. 존재하면 `/api/{namespace}/...` 형태 검증은 서비스 계층에서 수행 |
  | `upstreamUrl` | String | 선택 (nullable) | `pathPrefix`와 반드시 쌍으로 존재해야 함. 존재하면 SSRF 검증 서비스 계층에서 수행 |

  **쌍 검증 규칙**: `pathPrefix`와 `upstreamUrl` 중 **하나만** 존재하면 DTO 클래스 레벨 `@AssertTrue` Bean Validation이 400 `VALIDATION_FAILED`를 반환한다. 즉, (둘 다 null) 또는 (둘 다 non-null/non-blank)만 유효하다.

  > **설계 결정 — Bean Validation 위치**: 한 필드만 있는 경우 검증을 DTO의 `@AssertTrue`(컨트롤러 계층)에서 처리한다. 서비스 계층이 아닌 DTO에서 거부하는 이유: (1) 요청 형식 오류는 컨트롤러 계층의 책임이며 서비스 진입 전 차단이 바람직하다. (2) `MethodArgumentNotValidException` 핸들러가 이미 `VALIDATION_FAILED` + `fieldErrors` 응답을 반환하므로 별도 핸들러 불필요. (3) `@AssertTrue`의 field명은 `routeFields`로 지정해 fieldErrors에 표시한다.

- **응답 (성공) — 라우트 미생성 시**:
  - 상태: `201 Created`
  - 바디:
    ```json
    {
      "clientId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "clientSecret": "sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
      "routeId": null,
      "pathPrefix": null,
      "upstreamUrl": null,
      "enabled": null
    }
    ```

- **응답 (성공) — 라우트 생성 시**:
  - 상태: `201 Created`
  - 바디:
    ```json
    {
      "clientId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "clientSecret": "sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
      "routeId": "550e8400-e29b-41d4-a716-446655440000",
      "pathPrefix": "/api/eeos",
      "upstreamUrl": "http://eeos-service:8080",
      "enabled": true
    }
    ```

  > `clientSecret`은 응답 시 1회만 평문 노출된다. 재조회 불가. 라우트 `enabled` 기본값은 `true`(등록 즉시 활성화).

- **응답 (에러) — 기존 클라이언트 에러**:

  | HTTP | 에러 코드 | 발생 조건 |
  |------|-----------|-----------|
  | 400 | `VALIDATION_FAILED` | `@NotBlank`/`@NotNull` 위반 (`clientName` 빈 문자열, 단일 라우트 필드 등). `fieldErrors` 포함 |
  | 400 | `REDIRECT_URI_REQUIRED` | `redirectUris`가 null 또는 비어있음 (`RedirectUriRequiredException`) |
  | 401 | `AUTH_UNAUTHORIZED` | `X-User-Passport` 헤더 누락 또는 Passport 파싱 실패 (`PassportException`) |
  | 409 | `DUPLICATE_CLIENT_NAME` | `clientName` 중복 (`DuplicateClientNameException`) |
  | 422 | `CLIENT_LIMIT_EXCEEDED` | 회원당 최대 5개 초과 (`ClientLimitExceededException`) |

- **응답 (에러) — 라우트 생성 시 추가 에러**:

  | HTTP | 에러 코드 | 발생 조건 |
  |------|-----------|-----------|
  | 400 | `ROUTE_NAMESPACE_INVALID` | `pathPrefix`가 `/api/{namespace}` 형태가 아님 (`RouteNamespaceInvalidException`) |
  | 400 | `ROUTE_UPSTREAM_INVALID` | `upstreamUrl` SSRF 검증 실패 (`RouteUpstreamInvalidException`) |
  | 403 | `ROUTE_NAMESPACE_TAKEN` | 네임스페이스를 다른 `ownerId`의 회원이 이미 선점 (`RouteNamespaceTakenException`) |
  | 403 | `ROUTE_PROTECTED` | `pathPrefix`가 보호 경로 패턴과 충돌 (`RouteProtectedException`) |
  | 409 | `ROUTE_PATH_CONFLICT` | `pathPrefix` UNIQUE 제약 위반 (`RoutePathConflictException`) |

  > 라우트 관련 에러 발생 시 요청 전체가 실패하며 클라이언트도 생성되지 않는다 (원자성 보장: 동일 `@Transactional` 경계).

- **라우트 생성 검증 순서** (클라이언트 검증 5단계 완료 후 수행):
  1. `RouteNamespaceExtractor.extract(pathPrefix)` — 형식 위반 시 `ROUTE_NAMESPACE_INVALID` (400)
  2. `serviceRouteRepository.findNamespaceOwner(namespace)` — 타 owner 선점 시 `ROUTE_NAMESPACE_TAKEN` (403)
  3. `routeValidator.validateUpstreamUrl(upstreamUrl)` — SSRF 위반 시 `ROUTE_UPSTREAM_INVALID` (400)
  4. `routeValidator.validatePathPrefix(pathPrefix)` — 보호경로 충돌 시 `ROUTE_PROTECTED` (403), 중복 시 `ROUTE_PATH_CONFLICT` (409)

- **인증 / 권한**:
  - 필요 여부: 필수
  - 어노테이션: `@PassportAuth` (옵션 기본값 — `required=true`, `validateExpiry=true`, `requiredRoles={}`)
  - 필요 역할/스코프: 없음 (인증된 에코노 회원이면 모두 허용. ADR-0013)
  - 추가 조건: `passport.getMemberId()`를 `ownerId`로 사용. 라우트 생성 시 `service_route.owner_id = memberId`로 저장. ADMIN 역할 불필요.

- **비고**:
  - 라우트 생성 성공 시 `GatewayRefreshClient.triggerRefresh()`를 `TransactionSynchronizationManager.afterCommit` 콜백으로 호출한다. refresh 실패 시 경고 로그만 남기고 201 응답은 정상 반환 (api-gateway 재기동 시 초기 로드로 자동 복구).
  - 1 클라이언트 = 최대 1 라우트. 동일 클라이언트에 두 번째 라우트 등록 엔드포인트는 없다.
  - 라우트 조회/수정/삭제 별도 엔드포인트는 이번 범위 밖. 추후 클라이언트 목록/상세 조회 API 설계 시 포함 예정.
  - `routeId`는 `service_route.id` UUID 문자열.

---

### Swagger (`ClientApiDocs`) 갱신 사항

기존 `ClientApiDocs` 인터페이스의 `registerClient` 메서드에 다음을 추가한다.

**`@Operation` description 변경**:
- 기존: "항상 authorization_code (PKCE) 클라이언트로 등록된다. `redirectUris` 필수. 회원당 최대 5개 제한."
- 변경: "항상 authorization_code (PKCE) 클라이언트로 등록된다. `redirectUris` 필수. 회원당 최대 5개 제한.\n\n`pathPrefix`와 `upstreamUrl`을 함께 제공하면 동일 트랜잭션에서 Gateway 동적 라우트를 1건 생성하고 즉시 반영한다. 두 필드 중 하나만 제공하면 400 VALIDATION_FAILED."

**`@ApiResponses`에 추가할 응답 코드**:

| 추가 `responseCode` | 추가 `description` |
|---------------------|-------------------|
| `400` (기존 항목에 병합) | `ROUTE_NAMESPACE_INVALID` — pathPrefix가 `/api/{namespace}` 형태가 아님 |
| `400` (기존 항목에 병합) | `ROUTE_UPSTREAM_INVALID` — upstreamUrl SSRF 검증 실패 |
| `403` (신규) | `ROUTE_NAMESPACE_TAKEN` — 네임스페이스를 다른 회원이 선점 |
| `403` (신규) | `ROUTE_PROTECTED` — pathPrefix가 보호 경로와 충돌 |
| `409` (기존 항목에 병합) | `ROUTE_PATH_CONFLICT` — pathPrefix 중복 |

**응답 스키마**: 기존 `@ApiResponse(responseCode = "201")` content를 `SelfRegisterClientResponse`에서 라우트 필드가 추가된 버전으로 교체 (null 가능 필드 포함).

---

### 에러 코드 전체 표 (`POST /api/v1/clients`)

| HTTP 상태 | 에러 코드 | 예외 클래스 | 발생 조건 | 기존/신규 |
|-----------|-----------|------------|-----------|-----------|
| 400 | `VALIDATION_FAILED` | `MethodArgumentNotValidException` | Bean Validation 위반 (clientName 빈값, 단일 라우트 필드) | 기존 |
| 400 | `REDIRECT_URI_REQUIRED` | `RedirectUriRequiredException` | redirectUris null 또는 비어있음 | 기존 |
| 400 | `ROUTE_NAMESPACE_INVALID` | `RouteNamespaceInvalidException` | pathPrefix가 `/api/{namespace}` 형태가 아님 | 기존 (lib 구현 완료) |
| 400 | `ROUTE_UPSTREAM_INVALID` | `RouteUpstreamInvalidException` | upstreamUrl SSRF 검증 실패 | 기존 (lib 구현 완료) |
| 401 | `AUTH_UNAUTHORIZED` | `PassportException` | X-User-Passport 헤더 누락 또는 Passport 파싱 실패 | 기존 |
| 403 | `ROUTE_NAMESPACE_TAKEN` | `RouteNamespaceTakenException` | 네임스페이스를 다른 ownerId 회원이 선점 | 기존 (lib 구현 완료) |
| 403 | `ROUTE_PROTECTED` | `RouteProtectedException` | pathPrefix가 보호 경로 패턴과 충돌 | 기존 (lib 구현 완료) |
| 409 | `DUPLICATE_CLIENT_NAME` | `DuplicateClientNameException` | clientName 중복 | 기존 |
| 409 | `ROUTE_PATH_CONFLICT` | `RoutePathConflictException` | pathPrefix UNIQUE 제약 위반 | 기존 (lib 구현 완료) |
| 422 | `CLIENT_LIMIT_EXCEEDED` | `ClientLimitExceededException` | 회원당 최대 5개 초과 | 기존 |

> 라우트 관련 에러 코드 5종(`ROUTE_NAMESPACE_INVALID`, `ROUTE_UPSTREAM_INVALID`, `ROUTE_NAMESPACE_TAKEN`, `ROUTE_PROTECTED`, `ROUTE_PATH_CONFLICT`)은 모두 `service-client` lib의 기존 예외 클래스와 `GlobalExceptionHandler` 핸들러를 **그대로 재사용**한다. 신규 예외 클래스 또는 핸들러 정의 불필요.

---

### 폐기된 엔드포인트 (삭제 대상)

이전 설계에서 정의된 아래 엔드포인트는 이번 재설계로 폐기된다. 구현 코드(`RouteController`, `RouteApiDocs`, DTO, `SelfManageRouteUseCase`, `SelfManageRouteService` 등)는 todo.md "제거" 섹션에 따라 삭제한다.

| (폐기) 메서드 | (폐기) 경로 | 폐기 사유 |
|--------------|------------|-----------|
| POST | `/api/v1/routes` | `POST /api/v1/clients`에 흡수 |
| GET | `/api/v1/routes` | 이번 범위 밖 — 추후 클라이언트 목록/상세 API에서 제공 예정 |
| GET | `/api/v1/routes/{routeId}` | 이번 범위 밖 |
| PUT | `/api/v1/routes/{routeId}` | 이번 범위 밖 |
| DELETE | `/api/v1/routes/{routeId}` | 이번 범위 밖 |

---

## 체크리스트
- [x] todo의 모든 API 작업이 엔드포인트로 명세됨 (`POST /api/v1/clients` 변경 + Admin 유지)
- [x] 각 엔드포인트의 인증/권한이 명시됨 (`@PassportAuth` 역할 제약 없음, memberId 필수)
- [x] 모든 에러 케이스가 기존 에러 체계로 매핑됨 (신규 예외 클래스/핸들러 정의 없음)
- [x] 요청·응답 스키마가 실제 본문 예시로 작성됨 (라우트 미생성/생성 두 가지 케이스)
- [x] 프로젝트 표준 헤더(`X-User-Passport`)가 누락 없이 명시됨
- [x] Bean Validation 위치 결정 (DTO `@AssertTrue` — 컨트롤러 계층)
- [x] `ClientApiDocs` Swagger 갱신 사항 명시됨
- [x] 폐기 엔드포인트 목록 명시됨

---

## 참고
- `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/controller/ClientController.java` — 흡수 대상 컨트롤러
- `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/docs/ClientApiDocs.java` — Swagger 갱신 대상
- `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/dto/SelfRegisterClientRequest.java` — DTO 확장 대상
- `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/dto/SelfRegisterClientResponse.java` — DTO 확장 대상
- `services/apis/auth-api/src/main/java/com/econo/auth/api/exception/GlobalExceptionHandler.java` — 라우트 에러 핸들러 기확인 (재사용)
- `services/libs/service-client/src/main/java/com/econo/auth/client/application/usecase/RegisterOAuthClientUseCase.java` — Command/Result 확장 대상
- `services/libs/service-client/src/main/java/com/econo/auth/client/application/service/RegisterOAuthClientService.java` — selfRegister 확장 대상
- `docs/DYNAMIC_ROUTING.md` — 에러코드/SSRF/보호경로 표
- `docs/adr/0013-passport-member-self-registration.md` — 셀프서비스 인증 모델 근거
- `docs/adr/0017-gateway-tokenless-passthrough.md` — passthrough 인증 모델
