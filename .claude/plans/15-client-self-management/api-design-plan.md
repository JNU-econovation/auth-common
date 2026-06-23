# client-self-management - api-design

## 메타
- **작업명**: client-self-management
- **문서 타입**: api-design
- **작성일**: 2026-06-22
- **관련 문서** (같은 디렉터리):
  - todo.md
  - implementation-plan.md
  - db-design-plan.md

---

## 개요

ADR-0018(POST /api/v1/clients 라우트 흡수)의 후속으로, 인증된 에코노 회원이 자신이 등록한 OAuth 클라이언트와 연결 라우트를 직접 조회·수정·삭제할 수 있는 셀프서비스 API 4개를 `GET /api/v1/clients`, `GET /api/v1/clients/{clientId}`, `PUT /api/v1/clients/{clientId}`, `DELETE /api/v1/clients/{clientId}`로 `ClientController`에 추가한다. 프로토콜은 REST(Spring MVC, JSON), 인증은 econo-passport `@PassportAuth`(Gateway 주입 `X-User-Passport` 헤더), 에러 응답은 기존 `ApiError` 4-필드 레코드 체계(`errorCode`, `message`, `timestamp`, `fieldErrors`)를 그대로 사용한다.

---

## 본문

### 엔드포인트 목록

| 메서드 | 경로 | 설명 | 인증 / 권한 | 연관 todo |
|--------|------|------|-------------|-----------|
| GET | `/api/v1/clients` | 내 클라이언트 목록 (연결 라우트 포함) | `@PassportAuth` — 역할 제약 없음, memberId 필수 | API 작업 #1 |
| GET | `/api/v1/clients/{clientId}` | 단건 상세 (연결 라우트 포함) | `@PassportAuth` — 역할 제약 없음, memberId 필수 | API 작업 #2 |
| PUT | `/api/v1/clients/{clientId}` | 전체 교체 수정 (full representation) | `@PassportAuth` — 역할 제약 없음, memberId 필수 | API 작업 #3 |
| DELETE | `/api/v1/clients/{clientId}` | 하드 삭제 (클라이언트 + SAS + 라우트 캐스케이드) | `@PassportAuth` — 역할 제약 없음, memberId 필수 | API 작업 #4 |

---

### 공통 사항

#### 인증 헤더

| 헤더 | 출처 | 설명 |
|------|------|------|
| `X-User-Passport` | api-gateway 자동 주입 | Base64(JSON(Passport)). econo-passport `PassportArgumentResolver`가 파싱. 누락 또는 invalid → 401 `AUTH_UNAUTHORIZED`. |

#### 에러 응답 공통 포맷

```json
{
  "errorCode": "CLIENT_NOT_FOUND",
  "message": "존재하지 않는 클라이언트입니다.",
  "timestamp": "2026-06-22T10:00:00",
  "fieldErrors": null
}
```

`fieldErrors`는 Bean Validation(`VALIDATION_FAILED`) 시에만 배열로 채워진다. 나머지는 `null`.

#### 라우팅 보호

`/api/v1/clients/**` 경로는 `GatewayRoutingConfig`의 `auth-clients` 정적 보호 라우트로 auth-api에 프록시된다. SecurityConfig 추가 URL 패턴 불필요 — `@PassportAuth`가 인증 경계를 담당한다.

---

### 엔드포인트 상세

---

#### GET /api/v1/clients

- **목적**: 로그인한 회원이 자신이 소유한 OAuth 클라이언트 전체 목록을 조회한다. 각 항목에 연결된 `service_route` 정보를 포함한다. `clientSecret`은 절대 반환하지 않는다.
- **연관 todo**: `[ ] GET /api/v1/clients — 내 클라이언트 목록 엔드포인트 노출. 응답에 각 클라이언트별 연결 라우트 정보(routeId, pathPrefix, upstreamUrl, enabled) 포함. clientSecret 미반환. 200 OK.`
- **요청 헤더**:
  - `X-User-Passport: <Gateway 주입 Base64 Passport>` (필수)
- **요청 파라미터**: 없음
- **요청 바디**: 없음
- **응답 (성공)**:
  - 상태: `200 OK`
  - 바디:
    ```json
    {
      "clients": [
        {
          "clientId": "550e8400-e29b-41d4-a716-446655440000",
          "clientName": "EEOS 웹앱",
          "redirectUris": ["https://app.econovation.kr/callback"],
          "route": {
            "routeId": "a316bc69-1234-5678-abcd-ef0123456789",
            "pathPrefix": "/api/eeos",
            "upstreamUrl": "http://eeos-service:8080",
            "enabled": true
          }
        },
        {
          "clientId": "661f9511-f30c-52e5-b827-557766551111",
          "clientName": "EEOS 앱",
          "redirectUris": ["eeos://callback"],
          "route": null
        }
      ]
    }
    ```
  - `route`는 연결된 `service_route`가 없으면 `null`.
  - 빈 목록이면 `"clients": []`.
- **응답 (에러)**:
  - `401` `AUTH_UNAUTHORIZED` — `X-User-Passport` 헤더 누락 또는 파싱 실패
  - `400` `AUTH_BAD_REQUEST` — `X-User-Passport` Base64/JSON 파싱 불가 (econo-passport)
- **인증 / 권한**:
  - 필요 여부: 필수
  - `@PassportAuth` — 역할(Role) 제약 없음. `passport.getMemberId()`로 소유자 필터링.
  - 추가 조건: `ownerId == passport.getMemberId()`인 `service_client` 행만 조회. 타인 소유 항목은 결과에 포함되지 않는다.
- **비고**:
  - `@Transactional(readOnly = true)` 적용.
  - 페이지네이션 없음 — 회원당 최대 5개 제한이 있으므로 전량 반환.

---

#### GET /api/v1/clients/{clientId}

- **목적**: 단건 클라이언트 상세 조회. 소유권 검증이 포함된다. 타인 소유 클라이언트는 존재 여부를 은닉하여 404로 응답한다.
- **연관 todo**: `[ ] GET /api/v1/clients/{clientId} — 단건 상세 조회 엔드포인트 노출. 연결 라우트 정보 포함. clientSecret 미반환. 200 OK. 본인 소유가 아니면 404 CLIENT_NOT_FOUND.`
- **요청 헤더**:
  - `X-User-Passport: <Gateway 주입 Base64 Passport>` (필수)
- **경로 파라미터**:
  - `clientId` — `String(UUID)`, 조회 대상 OAuth 클라이언트 ID
- **요청 바디**: 없음
- **응답 (성공)**:
  - 상태: `200 OK`
  - 바디:
    ```json
    {
      "clientId": "550e8400-e29b-41d4-a716-446655440000",
      "clientName": "EEOS 웹앱",
      "redirectUris": ["https://app.econovation.kr/callback"],
      "route": {
        "routeId": "a316bc69-1234-5678-abcd-ef0123456789",
        "pathPrefix": "/api/eeos",
        "upstreamUrl": "http://eeos-service:8080",
        "enabled": true
      }
    }
    ```
  - `route`는 연결된 `service_route`가 없으면 `null`.
- **응답 (에러)**:
  - `404` `CLIENT_NOT_FOUND` — clientId가 존재하지 않거나 본인 소유가 아님 (존재 은닉). `InvalidClientException` → `GlobalExceptionHandler.handleInvalidClient` 기존 핸들러가 처리.
  - `401` `AUTH_UNAUTHORIZED` — `X-User-Passport` 헤더 누락 또는 파싱 실패
- **인증 / 권한**:
  - 필요 여부: 필수
  - `@PassportAuth` — 역할 제약 없음.
  - 추가 조건: `serviceClientRepository.findByClientIdAndOwnerId(clientId, memberId)`로 소유권을 원자적으로 검증. empty이면 `InvalidClientException` → 404.
- **비고**:
  - `@Transactional(readOnly = true)` 적용.

---

#### PUT /api/v1/clients/{clientId}

- **목적**: 클라이언트의 전체 표현(full representation)을 교체한다. `SelfRegisterClientRequest`와 동일 필드 구조의 `UpdateMyClientRequest`를 수신한다. 백엔드가 현재 상태와 diff하여 변경분만 반영한다. `clientSecret` 재발급 없음. 라우트는 PUT 요청의 라우트 필드 유무에 따라 upsert 또는 삭제된다.
- **연관 todo**: `[ ] PUT /api/v1/clients/{clientId} — 전체 표현(full representation) 수정 엔드포인트 노출. 요청 바디를 SelfRegisterClientRequest와 동일 구조(clientName, redirectUris 필수 + pathPrefix, upstreamUrl 선택)로 설계. 200 OK. 본인 소유가 아니면 404. 네임스페이스 변경 시도 시 400 ROUTE_NAMESPACE_CHANGE_DENIED.`
- **요청 헤더**:
  - `X-User-Passport: <Gateway 주입 Base64 Passport>` (필수)
  - `Content-Type: application/json` (필수)
- **경로 파라미터**:
  - `clientId` — `String(UUID)`, 수정 대상 OAuth 클라이언트 ID
- **요청 바디** (`UpdateMyClientRequest` — `SelfRegisterClientRequest`와 동일 구조):

  | 필드 | 타입 | 필수 | 검증 | 설명 |
  |------|------|------|------|------|
  | `clientName` | `String` | 필수 | `@NotBlank` | OAuth 클라이언트 이름. 빈 문자열 불가. |
  | `redirectUris` | `Set<String>` | 필수 | `@NotNull` + 비어있으면 `RedirectUriRequiredException` | 허용 리다이렉트 URI 목록. |
  | `pathPrefix` | `String` | 선택 | `@AssertTrue isRouteFields()` | 라우트 경로 접두사. `upstreamUrl`과 반드시 쌍으로 제공. 생략 시 기존 라우트 삭제. |
  | `upstreamUrl` | `String` | 선택 | `@AssertTrue isRouteFields()` | 업스트림 서비스 URL. `pathPrefix`와 반드시 쌍. SSRF 검증 대상. |

  > `isRouteFields()` boolean 게터는 `@Schema(hidden = true)` 적용 필수 — springdoc 오노출 방지 (`SelfRegisterClientRequest`와 동일 패턴).

  ```json
  {
    "clientName": "EEOS 웹앱 v2",
    "redirectUris": ["https://app.econovation.kr/callback", "https://dev.econovation.kr/callback"],
    "pathPrefix": "/api/eeos",
    "upstreamUrl": "http://eeos-service-v2:8080"
  }
  ```

  라우트 삭제 의도 시 (pathPrefix·upstreamUrl 생략):
  ```json
  {
    "clientName": "EEOS 웹앱 v2",
    "redirectUris": ["https://app.econovation.kr/callback"]
  }
  ```

- **응답 (성공)**:
  - 상태: `200 OK`
  - 바디: 수정 후 상태 (GET 단건 조회 응답과 동일 구조 `MyClientDetailResponse`):
    ```json
    {
      "clientId": "550e8400-e29b-41d4-a716-446655440000",
      "clientName": "EEOS 웹앱 v2",
      "redirectUris": ["https://app.econovation.kr/callback", "https://dev.econovation.kr/callback"],
      "route": {
        "routeId": "a316bc69-1234-5678-abcd-ef0123456789",
        "pathPrefix": "/api/eeos",
        "upstreamUrl": "http://eeos-service-v2:8080",
        "enabled": true
      }
    }
    ```
  - 라우트가 삭제된 경우: `"route": null`.
- **응답 (에러)** — 기존 에러 체계 사용:

  | HTTP | 에러 코드 | 발생 조건 |
  |------|-----------|-----------|
  | 400 | `VALIDATION_FAILED` | `clientName` 빈값 (`@NotBlank`), 또는 `pathPrefix`·`upstreamUrl` 중 하나만 제공 (`isRouteFields()` 위반). `fieldErrors` 배열 포함. |
  | 400 | `REDIRECT_URI_REQUIRED` | `redirectUris` null 또는 비어있음. `RedirectUriRequiredException` → 기존 핸들러 처리. |
  | 400 | `ROUTE_NAMESPACE_INVALID` | `pathPrefix`가 `/api/{namespace}/...` 형태 불일치. `RouteNamespaceInvalidException` → 기존 핸들러 처리. |
  | 400 | `ROUTE_UPSTREAM_INVALID` | `upstreamUrl` SSRF 검증 실패. `RouteUpstreamInvalidException` → 기존 핸들러 처리. |
  | 400 | `ROUTE_NAMESPACE_CHANGE_DENIED` | `pathPrefix`의 네임스페이스가 기존 라우트와 다름. `RouteNamespaceChangeException`(신규) → 신규 핸들러 추가. |
  | 401 | `AUTH_UNAUTHORIZED` | `X-User-Passport` 헤더 누락 또는 파싱 실패. |
  | 403 | `ROUTE_NAMESPACE_TAKEN` | 네임스페이스를 다른 회원이 선점. `RouteNamespaceTakenException` → 기존 핸들러 처리. |
  | 403 | `ROUTE_PROTECTED` | `pathPrefix`가 보호 경로와 충돌. `RouteProtectedException` → 기존 핸들러 처리. |
  | 404 | `CLIENT_NOT_FOUND` | clientId 미존재 또는 타인 소유. `InvalidClientException` → 기존 핸들러 처리. |
  | 409 | `ROUTE_PATH_CONFLICT` | `pathPrefix` 중복 (다른 클라이언트와 충돌). `RoutePathConflictException` → 기존 핸들러 처리. |

- **인증 / 권한**:
  - 필요 여부: 필수
  - `@PassportAuth` — 역할 제약 없음.
  - 추가 조건: `findByClientIdAndOwnerId(clientId, memberId)` 소유권 확인 선행. 실패 시 404 (존재 은닉). 소유권 확인 통과 후에만 수정 로직 진입.
- **라우트 diff 처리 규칙** (검증 순서):
  1. 소유권 확인 (404 은닉)
  2. 요청 바디 Bean Validation (`VALIDATION_FAILED`, `REDIRECT_URI_REQUIRED`)
  3. 라우트 필드가 있는 경우 네임스페이스 포맷 검증 (`ROUTE_NAMESPACE_INVALID`)
  4. 기존 라우트 있고 요청 라우트 있음: 네임스페이스 불변 검증 (`ROUTE_NAMESPACE_CHANGE_DENIED`)
  5. SSRF 검증 (`ROUTE_UPSTREAM_INVALID`)
  6. 보호 경로 검증 (`ROUTE_PROTECTED`)
  7. 네임스페이스 선점 검증 (`ROUTE_NAMESPACE_TAKEN`) — 본인 네임스페이스는 허용
  8. 경로 중복 검증 (`ROUTE_PATH_CONFLICT`)
  9. 변경 분 반영 + afterCommit `GatewayRefreshClient.triggerRefresh()` 등록 (라우트 변동 있을 때만)
- **비고**:
  - `@Transactional` (read-write). 단일 트랜잭션 경계 내에서 clientName 수정·redirectUris 교체·라우트 diff 원자적 처리.
  - `DUPLICATE_CLIENT_NAME` 은 PUT에서 발생할 수 있음 — `DuplicateClientNameException` → 기존 `handleDuplicateClientName` 핸들러(409)가 처리. 별도 명시 필요.
  - 게이트웨이 refresh 실패는 경고 로그만 남기고 200 반환 (`ManageRouteService` 패턴 동일).

---

#### DELETE /api/v1/clients/{clientId}

- **목적**: 클라이언트를 하드 삭제한다. `service_client`, SAS `oauth2_registered_client`, 연결 `service_route`를 단일 트랜잭션에서 캐스케이드 삭제하고, 라우트가 있었으면 게이트웨이 refresh를 afterCommit에 등록한다.
- **연관 todo**: `[ ] DELETE /api/v1/clients/{clientId} — 하드 삭제 엔드포인트 노출. 204 No Content. 본인 소유가 아니면 404 CLIENT_NOT_FOUND.`
- **요청 헤더**:
  - `X-User-Passport: <Gateway 주입 Base64 Passport>` (필수)
- **경로 파라미터**:
  - `clientId` — `String(UUID)`, 삭제 대상 OAuth 클라이언트 ID
- **요청 바디**: 없음
- **응답 (성공)**:
  - 상태: `204 No Content`
  - 바디: 없음
- **응답 (에러)**:
  - `404` `CLIENT_NOT_FOUND` — clientId 미존재 또는 타인 소유 (존재 은닉). `InvalidClientException` → 기존 핸들러 처리.
  - `401` `AUTH_UNAUTHORIZED` — `X-User-Passport` 헤더 누락 또는 파싱 실패.
- **인증 / 권한**:
  - 필요 여부: 필수
  - `@PassportAuth` — 역할 제약 없음.
  - 추가 조건: `findByClientIdAndOwnerId(clientId, memberId)` 소유권 확인 선행. 실패 시 404 (존재 은닉).
- **삭제 순서** (단일 `@Transactional`):
  1. 소유권 확인 → 실패 시 `InvalidClientException` (404)
  2. 연결 라우트 존재 여부 확인 (`serviceRouteRepository.findByRegisteredClientId(clientId)`)
  3. 라우트 있으면 `serviceRouteRepository.deleteByRegisteredClientId(clientId)` + afterCommit refresh 등록
  4. `serviceClientRepository.deleteByClientId(clientId)` — `service_client` 삭제
  5. `sasClientRegistrar.unregisterClient(clientId)` — SAS `oauth2_registered_client` 직접 DELETE
- **비고**:
  - 멱등성: 이미 삭제된 clientId에 대한 재요청은 소유권 조회에서 empty → 404 반환 (204 멱등 처리 없음 — 존재 은닉 정책 우선).
  - SAS `RegisteredClientRepository` 표준 인터페이스에 delete가 없으므로 `JdbcTemplate`으로 `oauth2_registered_client` 테이블 직접 DELETE 필요 (SAS 1.x 의존성 주석 명시).

---

### 신규 예외 코드 정의

이번 작업에서 추가되는 예외는 다음 1개다.

| 예외 클래스 | 에러 코드 | HTTP | 발생 조건 |
|------------|-----------|------|-----------|
| `RouteNamespaceChangeException` (신규, `service-client` 모듈) | `ROUTE_NAMESPACE_CHANGE_DENIED` | 400 | PUT 수정 시 `pathPrefix`의 네임스페이스(두 번째 세그먼트)가 기존 라우트와 다름 |

> HTTP 상태 결정 근거: 네임스페이스 변경은 "새 등록 + 기존 삭제"에 해당하는 의미적 변경으로, 현재 엔드포인트가 허용하지 않는 동작이다. 이미 선점된 자원과의 충돌(409)이 아니라 요청 자체가 정책 위반(400 Bad Request)이므로 400을 선택한다. 403 Forbidden보다 400이 더 명확하다 — 권한 부족이 아니라 요청 의미가 잘못된 것.
>
> 예외 메시지 예시: `"네임스페이스는 변경할 수 없습니다. 기존=eeos, 요청=eeos-v2. 다른 네임스페이스를 사용하려면 클라이언트를 새로 등록하세요."`

`GlobalExceptionHandler`에 추가할 핸들러:
```java
@ExceptionHandler(RouteNamespaceChangeException.class)
public ResponseEntity<ApiError> handleRouteNamespaceChange(RouteNamespaceChangeException ex) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new ApiError("ROUTE_NAMESPACE_CHANGE_DENIED", ex.getMessage()));
}
```

---

### DTO 설계 요약

| DTO 클래스 | 방향 | 용도 | 비고 |
|-----------|------|------|------|
| `UpdateMyClientRequest` | 요청 | PUT 수정 요청. `SelfRegisterClientRequest`와 동일 필드 구조 | `isRouteFields()` `@Schema(hidden=true)` 필수 |
| `MyClientRouteInfo` | 응답 (중첩) | 라우트 정보 `routeId`, `pathPrefix`, `upstreamUrl`, `enabled`. null 허용 | GET 단건/목록/PUT 응답 공용 |
| `MyClientItemResponse` | 응답 | 목록 항목 1건 | `clientId`, `clientName`, `redirectUris`, `route(MyClientRouteInfo)` |
| `MyClientListResponse` | 응답 | 목록 전체 래퍼 | `clients: List<MyClientItemResponse>` |
| `MyClientDetailResponse` | 응답 | 단건 상세 / PUT 수정 후 상태 | `MyClientItemResponse`와 동일 구조 — 통합 여부는 구현자 결정 가능 |

> `MyClientItemResponse`와 `MyClientDetailResponse`가 필드 구조가 동일하므로 단일 클래스로 통합하거나 `MyClientItemResponse`를 공용으로 재사용해도 무방하다.

---

### 에러 응답 전체 매트릭스

| HTTP | 에러 코드 | 예외 클래스 | 발생 엔드포인트 | 핸들러 |
|------|-----------|------------|----------------|--------|
| 400 | `AUTH_BAD_REQUEST` | `PassportException` (econo-passport) | 전체 | 기존 `handlePassportException` |
| 400 | `VALIDATION_FAILED` | `MethodArgumentNotValidException` | PUT | 기존 `handleValidation` (fieldErrors 포함) |
| 400 | `REDIRECT_URI_REQUIRED` | `RedirectUriRequiredException` | PUT | 기존 `handleRedirectUriRequired` |
| 400 | `ROUTE_NAMESPACE_INVALID` | `RouteNamespaceInvalidException` | PUT | 기존 `handleRouteNamespaceInvalid` |
| 400 | `ROUTE_UPSTREAM_INVALID` | `RouteUpstreamInvalidException` | PUT | 기존 `handleRouteUpstreamInvalid` |
| 400 | `ROUTE_NAMESPACE_CHANGE_DENIED` | `RouteNamespaceChangeException` (신규) | PUT | **신규 핸들러 추가 필요** |
| 401 | `AUTH_UNAUTHORIZED` | `PassportException` (econo-passport) | 전체 | 기존 `handlePassportException` |
| 403 | `ROUTE_NAMESPACE_TAKEN` | `RouteNamespaceTakenException` | PUT | 기존 `handleRouteNamespaceTaken` |
| 403 | `ROUTE_PROTECTED` | `RouteProtectedException` | PUT | 기존 `handleRouteProtected` |
| 404 | `CLIENT_NOT_FOUND` | `InvalidClientException` | GET 단건, PUT, DELETE | 기존 `handleInvalidClient` |
| 409 | `DUPLICATE_CLIENT_NAME` | `DuplicateClientNameException` | PUT | 기존 `handleDuplicateClientName` |
| 409 | `ROUTE_PATH_CONFLICT` | `RoutePathConflictException` | PUT | 기존 `handleRoutePathConflict` |

> `INVALID_ARGUMENT`(400, `IllegalArgumentException`) — PUT 서비스 계층 방어선에서 발생 가능. 기존 `handleIllegalArgument`가 처리.

---

## 체크리스트
- [x] todo의 모든 API 작업(#1~#5)이 엔드포인트로 명세됨
- [x] 각 엔드포인트의 인증/권한이 명시됨 (`@PassportAuth` 역할 제약 없음, memberId 기반 소유권 조건 명시)
- [x] 모든 에러 케이스가 기존 에러 체계로 매핑됨 (신규 예외 1개 별도 항목 명시)
- [x] 요청·응답 스키마가 실제 JSON 본문 예시로 작성됨
- [x] 프로젝트 표준 헤더(`X-User-Passport`) 누락 없이 명시됨
- [x] `isRouteFields()` boolean 게터 `@Schema(hidden=true)` 명시
- [x] 존재 은닉(404) 정책 모든 소유권 검증 엔드포인트에 명시
- [x] 라우트 diff 규칙 및 검증 순서 명시

---

## 참고
- `/docs/CLIENT_REGISTRATION.md` — 셀프 등록 운영 가이드, 에러 코드 체계 (본 작업 전편)
- `/docs/DYNAMIC_ROUTING.md` — 라우트 관리 API 설계 패턴, afterCommit refresh 흐름
- `services/apis/auth-api/README.md` — auth-api 전체 엔드포인트 레퍼런스
- `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/controller/ClientController.java` — 핸들러 추가 대상 컨트롤러
- `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/controller/AdminClientController.java` — `@PassportAuth(requiredRoles)` 패턴 참조
- `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/dto/SelfRegisterClientRequest.java` — `UpdateMyClientRequest` 구조 기준
- `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/dto/SelfRegisterClientResponse.java` — 응답 DTO 패턴 참조
- `services/apis/auth-api/src/main/java/com/econo/auth/api/exception/GlobalExceptionHandler.java` — 에러 핸들러 추가 대상, `ApiError` 포맷 확인
- `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/docs/ClientApiDocs.java` — Swagger 문서 추가 대상
