# ADR-0010: Admin API 인증을 Internal API Key에서 ClientSecret Self-Service로 전환

- **상태:** Accepted
- **결정일:** 2026-06-03
- **결정자:** Bellmin (kim.jongmin@cashwalk.io)

---

## 배경

`feat/member-auth` 브랜치(HEAD `2f1b3a5`)에서 SSO 시스템의 인증 모델을 점검하던 중, admin 영역에 **OAuth 2.0 표준에서 가져왔지만 우리 시스템에선 검증할 곳이 없는 죽은 필드와 인증 채널**이 다수 존재함을 확인했다.

### 죽은 코드 인벤토리 (코드 그렙으로 확인)

| 항목 | 위치 | 실제 사용처 |
|---|---|---|
| `grantType` 입력 + `GrantType` enum + `UnsupportedGrantTypeException` | `AdminClientController`, `RegisterOAuthClientService`, `domain/GrantType.java` | **0건** — `/oauth2/token` 부재로 검증할 흐름 없음 |
| `clientSecret` 발급 + BCrypt 해시 | `RegisterOAuthClientService.generateSecret()`, `SasClientRegistrarAdapter` | **0건** — 표준 OAuth 검증 흐름(`/token`) 없음 |
| `api_key_hash` (SHA-256) | `ServiceClient`, `ServiceClientJpaEntity` | **0건** — 저장만 되고 어디서도 읽지 않음 |
| `X-Internal-Api-Key` 헤더 인증 | `AdminClientController.isValidApiKey()` | **모든 admin endpoint** (살아있음) |

### 운영 모델과의 부정합

- SAS 인가서버는 24d70ea·af4047d 커밋에서 제거됨 (PKCE/`/oauth2/authorize`/`/oauth2/token` 일체 없음)
- 클라이언트 등록은 외부 self-service가 아니라 **운영팀이 직접 한다** — 즉 자동화된 키 기반 인증이 필요한 시나리오가 아님
- 그러나 redirect URI 같은 클라이언트별 메타데이터는 **그 클라이언트 운영자가 자기 정보만** 변경할 수 있어야 권한 분리가 자연스러움

→ 결과: Internal API Key는 admin endpoint 전체에 대해 "모 아니면 도" 인증을 강제하고, OAuth 표준 잔재(grantType, clientSecret, api_key_hash)는 검증 흐름 없이 DB 자리만 차지함.

---

## 결정

**Admin API의 인증 모델을 "Internal API Key 전체 보호"에서 "등록 public + redirect-uris CRUD는 clientSecret Basic Auth"로 전환하고, OAuth 표준 잔재(grantType, api_key_hash, Internal API Key 시스템 전체)를 폐기한다.**

### 새 인증 매트릭스

| Endpoint | 인증 | 비고 |
|---|---|---|
| `POST /api/v1/admin/clients` | **없음 (public)** | `clientName` + 선택 필드만 받고 `clientId` + `clientSecret` 발급 |
| `GET /api/v1/admin/routes` | **없음 (public)** | 운영 점검용, 민감 정보 없음 |
| `GET /api/v1/admin/clients/{clientId}` | Basic Auth (`clientId`:`clientSecret`) | path의 clientId == Basic의 clientId 강제 |
| `POST /api/v1/admin/clients/{clientId}/redirect-uris` | 위와 동일 | 자기 자원만 변경 |
| `DELETE /api/v1/admin/clients/{clientId}/redirect-uris` | 위와 동일 | 자기 자원만 변경 |
| `PUT /api/v1/admin/clients/{clientId}/redirect-uris` | 위와 동일 | 자기 자원만 변경 |

### 폐기 항목

- `grantType` 입력 필드 + `GrantType` enum + `parseGrantType()` + `UnsupportedGrantTypeException` + `GlobalExceptionHandler` 핸들러
- `RegisterOAuthClientService` 내 grant type별 분기
- `api_key_hash` (SHA-256) 컬럼 + `ServiceClient.apiKeyHash` 도메인 필드 + `SasClientRegistrarAdapter`의 SHA-256 해싱 로직
- `service_client.grant_type`, `service_client.api_key_hash` 컬럼 (Flyway drop 마이그레이션)
- 환경변수 `AUTH_INTERNAL_API_KEY` 사용 코드 + `isValidApiKey()` + 401 `UNAUTHORIZED` 분기 전부

### 유지 항목

- `clientSecret` 생성 (`generateSecret`) + BCrypt 해시 저장 + 등록 응답에 **1회 노출**
- `AdminClientController` 7개 엔드포인트 전부
- `redirectUris` CRUD, `service_route` 저장, SAS `RegisteredClient` 등록 (메타데이터 컨테이너 역할)
- `register-service` 스킬 (Internal API Key 호출 부분만 제거)

---

## 근거

### 대안 비교

| | 등록 방법 | Internal API Key | 운영 편의성 | 보안 |
|---|---|---|---|---|
| **X1 (DB 직접)** | 운영자가 DB INSERT (sh 스크립트 자동화) | 완전 제거 | 별도 도구 필요 | 등록 인증은 DB 권한으로 강제 |
| **X2 (선택)** | `POST /clients` **public** | 완전 제거 | sh 한 줄, HTTP 한 번 | 등록은 무방비, 변경은 secret |
| X3 | Internal API Key 등록에만 유지 | 등록에만 유지 | 동일 | 등록 보호 |
| 현재 (Status quo) | Internal API Key | 전체 보호 | 키 관리 부담 | 모든 admin 보호 |

### X2를 선택한 이유

1. **운영자 친화적** — 별도 인증 채널·도구 없이 단일 HTTP 호출로 등록. `register-service` 스킬도 secret 헤더 처리 없이 동작.
2. **권한 분리가 자연스러움** — "등록은 누구나, 자기 정보 변경은 자기만"이 직관적이고 OAuth 표준의 confidential client 패턴과 일관됨.
3. **죽은 자격증명 부활** — 발급은 하면서 검증은 0건이던 `clientSecret`이 실제 검증 흐름을 갖게 됨.
4. **인증 채널 단일화** — 두 종류(Internal API Key + 죽은 ClientSecret) → 한 종류(살아있는 ClientSecret). 신규 합류자 혼란 감소.

### X1을 선택하지 않은 이유

DB 직접 INSERT는 BCrypt 해싱·UUID 생성·route_id 생성 등 운영자 부담이 크고, 자동화 sh 스크립트를 만든다 해도 결국 "또 다른 admin tooling"이라 단순화 목표에 역행한다. HTTP 한 줄로 끝나는 X2가 더 깔끔하다.

### X3를 선택하지 않은 이유

"Internal API Key 시스템 자체가 없어도 될 것 같다"는 직전 의사 결정과 모순된다. 등록에만 남기는 건 Internal API Key 운영 부담을 그대로 짊어지면서 사용처만 1개로 줄이는 셈이라 단순화 효과가 작다.

---

## 결과

### 긍정적 영향

- **코드 단순화**: 죽은 코드 8개 항목 + 환경변수 1개 + DB 컬럼 2개 제거 → admin 영역 인지 부담 감소
- **인증 모델 일관성**: 단일 자격증명(`clientSecret`) + 표준 Basic Auth → Spring Security 기본 패턴 그대로 활용
- **권한 분리**: 운영자가 클라이언트별 redirect URI 일일이 손대지 않아도 됨 (클라이언트 셀프 서비스)
- **`register-service` 스킬 단순화**: `X-Internal-Api-Key` 헤더 주입 로직 제거

### 제약 사항 / 주의사항

- ⚠️ **`POST /admin/clients`가 public이라 외부 인터넷 노출 시 도배 공격 가능.** 시스템이 **내부망 전용** 또는 **신뢰된 네트워크** 라는 전제 위에서만 안전. 외부 노출 운영 시 별도 작업으로 **rate limiting** (per-IP 분당 5회 등) 필수.
- ⚠️ **BCrypt 비교 비용** — `redirectUris` CRUD 호출마다 BCrypt 검증 (~10ms). 트래픽이 적은 admin 영역이라 무시 가능하지만 대량 자동화 도구에선 고려 필요.
- ⚠️ **clientSecret 폐기·회전 절차 부재** — 현재 코드는 secret 재발급/회전 endpoint가 없음. 유출 시 클라이언트를 통째로 재등록해야 함. 필요해지면 별도 `POST /admin/clients/{id}/rotate-secret` 신설 검토.
- **Basic Auth는 평문 base64** — HTTPS 전제. 평문 HTTP 환경에서는 도청으로 secret 노출.
- **clientId path param ↔ Basic Auth clientId 일치 검증 누락 위험** — 구현 시 path의 `{clientId}`와 인증된 `clientId`가 다르면 반드시 403으로 분기해야 함. 누락 시 한 클라이언트가 다른 클라이언트의 redirect URI를 조작 가능.

### 재검토 조건

- **외부 3rd-party 클라이언트 통합 요구 발생 시** → OAuth Authorization Code Flow + PKCE + SAS 재도입 필요. `clientSecret`은 표준 `/oauth2/token` 검증 채널로 자연 통합되고, 이 ADR은 supersede.
- **클라이언트 등록 도배 공격 / DB 폭발 사고 발생 시** → `POST /clients` public 모델 재고. Captcha, rate limit, 또는 X1(DB 직접)로 회귀 검토.
- **운영팀이 직접 등록하던 모델이 변화해 외부 self-service가 필요해질 시** → 신원 검증 절차 추가 필요. 이 ADR의 "public 등록" 결정이 무방비라는 가정이 깨짐.

---

## 관련 문서

- [ADR-0001 — Cookie-based SSO over PKCE](./0001-cookie-based-sso-over-pkce.md): SAS 제거의 상위 결정. 이 ADR은 그 결정의 정리 작업에 해당.
- [ADR-0002 — Gateway as Auth Boundary](./0002-gateway-as-auth-boundary.md): Gateway 중심 인증 모델. admin은 이 흐름 밖이지만 일관성 유지.
- [ADR-0005 — Static YAML Routing over Dynamic](./0005-static-yaml-routing-over-dynamic.md): `service_route`가 사실상 죽은 메타데이터인 배경.
- Notion: [SSO API 명세 데이터베이스](https://www.notion.so/373cc28145a880d8a960e8b72de05347), [SSO 흐름 다이어그램](https://www.notion.so/373cc28145a881acb33ff56f7dd3574c)
- 후속 작업: `/plan client-auth-overhaul` → `/develop` 사이클로 구현 예정
