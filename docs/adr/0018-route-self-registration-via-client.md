# ADR-0018: 게이트웨이 라우트 셀프 등록을 클라이언트 셀프 등록에 흡수

- **상태:** Accepted
- **결정일:** 2026-06-22
- **결정자:** econovation 개발팀

---

## 배경

회원이 자율적으로 할 수 있는 것은 **OAuth 클라이언트 셀프 등록**(`POST /api/v1/clients`, ADR-0013)뿐이었다. 그런데 클라이언트만 등록해서는 로그인까지만 가능하고, 정작 자기 서비스가 게이트웨이를 통해 트래픽을 받으려면 **동적 라우트(`service_route`)** 가 필요했다. 동적 라우트 등록은 ADR-0016 이후 `POST /api/v1/admin/routes`로 **ADMIN/SUPER_ADMIN 전용**이었다.

결과적으로 회원은 "클라이언트는 등록했지만 라우팅은 안 되는" 반쪽 상태였고, 라우트를 붙이려면 매번 관리자를 거쳐야 하는 병목이 있었다. 이 병목을 제거해 인증된 에코노 회원이 자기 서비스를 끝까지 자율 연동할 수 있게 하는 것이 목표였다.

라우트 등록을 회원에게 여는 것은 클라이언트 등록과 **폭발 반경이 다르다**. 클라이언트는 본인 소유 OAuth 클라이언트 하나를 만드는 것이라 남에게 영향이 없지만, 라우트는 **게이트웨이 전역 설정**이라 다른 팀의 경로를 가로채거나(섀도잉), 내부망을 노출(SSRF)하거나, 트래픽을 탈취할 위험이 있다.

---

## 결정

**회원 라우트 등록을 별도 엔드포인트가 아니라 기존 클라이언트 셀프 등록(`POST /api/v1/clients`)에 선택적으로 흡수한다.**

구체적으로:

1. **요청 흡수** — `POST /api/v1/clients` 요청에 선택 필드 `pathPrefix`, `upstreamUrl`을 추가한다. 두 필드가 모두 있으면 같은 트랜잭션에서 `service_route` 1건을 생성하고, 둘 다 없으면 기존처럼 클라이언트만 생성한다. **한쪽만** 있으면 400 `VALIDATION_FAILED`(DTO `@AssertTrue`, field=`routeFields`). **1 클라이언트 = 최대 1 라우트.**
2. **원자성** — 라우트 검증/저장 실패 시 클라이언트 생성도 함께 롤백된다(동일 `@Transactional` 경계). 라우트는 커밋 후 `GatewayRefreshClient.triggerRefresh()`로 즉시 라이브된다.
3. **소유권** — `service_route.owner_id BIGINT NULL` 컬럼을 추가(V11)한다. 셀프 등록은 `X-User-Passport`의 memberId를 owner로 저장, 어드민 등록(`/api/v1/admin/routes`)은 NULL.
4. **네임스페이스 선점 모델** — 셀프 라우트의 `pathPrefix`는 `/api/{namespace}/...` 형태(두 번째 세그먼트가 네임스페이스)로 강제한다. 네임스페이스를 처음 등록한 회원이 소유자가 되고, 같은 네임스페이스 하위는 같은 owner만 추가 가능하다. 타 owner가 침범하면 403 `ROUTE_NAMESPACE_TAKEN`.
5. **기존 방어 재사용** — SSRF 검증, 보호경로 정책(`ProtectedPathPolicy`), pathPrefix UNIQUE 중복 검증을 셀프 등록에도 동일 적용한다(`RouteValidator`로 추출해 어드민 경로와 공유).
6. **별도 회원 라우트 관리 엔드포인트는 만들지 않는다.** 조회/수정/삭제는 추후 클라이언트 목록/상세 조회 API가 라우팅 정보를 함께 노출하는 방식으로 다룬다. 어드민 라우트 CRUD(`/api/v1/admin/routes`)는 변경 없이 유지한다.

### 인증/에러 매트릭스

| 상황 | HTTP | 에러 코드 |
|---|---|---|
| 라우트 필드 한쪽만 제공 | 400 | `VALIDATION_FAILED` (field=`routeFields`) |
| `pathPrefix`가 `/api/{namespace}/` 형태 아님 | 400 | `ROUTE_NAMESPACE_INVALID` |
| 네임스페이스를 타 owner가 선점 | 403 | `ROUTE_NAMESPACE_TAKEN` |
| upstreamUrl SSRF 위반 | 400 | `ROUTE_UPSTREAM_INVALID` |
| 보호 경로 충돌 | 403 | `ROUTE_PROTECTED` |
| pathPrefix 중복 | 409 | `ROUTE_PATH_CONFLICT` |

---

## 근거

### 폐기한 대안 — 독립 라우트 CRUD 엔드포인트 (왜 A가 아니라 B)

처음에는 `/api/v1/routes`에 회원 셀프 **CRUD 5개**(POST/GET/GET{id}/PUT/DELETE)를 `AdminRouteController`처럼 미러링하는 설계로 구현까지 진행했다(owner_id + 쿼터 + PUT 네임스페이스 불변 + `/api/v1/routes/**` 보호경로 + 전용 예외 3종). 그러나 이 방식은 다음 이유로 폐기했다.

| 관점 | 독립 CRUD 5개 (폐기) | 클라이언트 등록 흡수 (채택) |
|---|---|---|
| 멘탈 모델 | "클라이언트 등록"과 "라우트 등록"이 따로 — 회원이 두 번 등록 | "내 서비스 등록 = 클라이언트 + 라우트 한 번에" |
| API 표면 | 신규 엔드포인트 5개 + DTO/문서/보호경로 | 기존 엔드포인트 1개에 선택 필드 2개 |
| 쿼터 | 라우트 전용 쿼터 별도 필요 | 클라이언트 5개 제한에 자연 종속(1:1) |
| 라우트 관리(수정/삭제) | 회원 셀프 CRUD로 구현 → 네임스페이스 불변/소유권 등 복잡도 증가 | 이번 범위 밖(추후 상세 조회 API), 표면 최소화 |

핵심은 **회원의 실제 멘탈 모델이 "서비스를 등록한다"는 단일 행위**라는 점이다. 클라이언트(OAuth 정체성)와 라우트(게이트웨이 경로)는 한 서비스의 두 측면이므로 한 번의 등록으로 묶는 것이 자연스럽고, 별도 CRUD가 만들어내는 표면·복잡도(쿼터, PUT 시 네임스페이스 변경 금지, 소유권 접근 제어, 자기 관리 경로 보호 등)를 통째로 제거할 수 있다.

### 네임스페이스 선점 모델을 택한 이유

라우트를 회원에게 열 때 가장 큰 신규 리스크는 **경로 섀도잉**(다른 팀이 쓸 prefix를 선점하거나 광범위 prefix를 잡아 트래픽 가로채기)이다. UNIQUE(pathPrefix) 제약과 보호경로 정책만으로는 prefix 겹침을 막지 못한다. `/api/{namespace}/` 컨벤션을 강제하고 네임스페이스 단위로 owner를 고정하면, 한 회원이 점유한 네임스페이스를 다른 회원이 침범할 수 없어 섀도잉을 구조적으로 차단한다. (대안인 "첫 세그먼트 = 네임스페이스"는 `/api/...` 사용 시 모두 `api`로 충돌, "겹침 금지만"은 컨벤션 없이 유연하나 선점 표현이 약함 — 둘 다 기각.)

### owner_id를 nullable로 둔 이유

어드민 등록 라우트와 셀프 등록 라우트를 한 테이블에서 공존시키되, 어드민 라우트는 특정 회원 소유가 아니므로 `owner_id = NULL`로 둔다(ADR-0013의 `service_client.owner_id` V7 패턴과 일관). NOT NULL 전환도 검토했으나, 기존 어드민 라우트에 합성 소유자를 부여해야 하고 어드민 등록 경로까지 owner를 강제해야 해서 이득 대비 변경 비용이 컸다.

---

## 결과

### 긍정적 영향

- 인증된 에코노 회원이 관리자 개입 없이 **클라이언트 + 게이트웨이 라우트를 한 번에** 등록해 자기 서비스를 끝까지 자율 연동할 수 있다.
- 별도 라우트 CRUD 표면(엔드포인트 5개 + 전용 DTO/예외/보호경로)이 사라져 유지보수 면적이 줄었다.
- 네임스페이스 선점으로 경로 섀도잉을, 기존 SSRF/보호경로 검증 재사용으로 내부망 노출·인증 경계 침범을 구조적으로 방어한다.
- 원자성 보장으로 "클라이언트는 생겼는데 라우트는 실패" 같은 부분 실패 상태가 없다.

### 제약 사항 / 주의사항

- ⚠️ **회원 라우트 조회/수정/삭제 수단이 현재 없다.** 잘못 등록한 라우트는 회원이 직접 고칠 수 없고, 추후 클라이언트 상세 조회 API + 라우트 관리 흐름이 도입되어야 한다.
- ⚠️ **1 클라이언트 = 1 라우트 고정.** 한 서비스가 여러 경로를 필요로 하면 현재 모델로는 부족하다(여러 클라이언트로 우회해야 함).
- ⚠️ **내부망 hostname 허용은 셀프 등록에도 동일하게 열려 있다.** SSRF 검증은 명시적 private IP/loopback만 차단하고, DNS 미조회 hostname(`http://svc:8080`)은 내부망 컨테이너로 간주해 허용한다(에코노 회원 전제의 트레이드오프, ADR-0016과 동일). 회원 신뢰 경계를 좁히려면 셀프 라우트만 공개 https 호스트로 제한하는 옵션을 재검토한다.
- ⚠️ **네임스페이스 선점 조회 시 어드민(owner=NULL) 라우트 공존 가능성.** 같은 네임스페이스에 어드민 라우트(owner NULL)와 셀프 라우트가 섞이면 조회가 복수 owner를 반환할 수 있어, 리포지토리는 List 반환 + null 필터 + 첫 non-null owner 방식으로 처리한다.

### 재검토 조건

- 회원이 라우트를 직접 조회/수정/삭제해야 하는 요구가 커질 때 — 클라이언트 상세 조회 API 또는 라우트 관리 흐름 설계와 함께 재검토.
- 한 서비스가 다중 경로를 요구해 1:1 제약이 병목이 될 때.
- 회원 신뢰 경계를 좁혀야 할 때(내부망 hostname 허용 제한 등).

---

## 관련 문서

- [ADR-0013](./0013-passport-member-self-registration.md) — 클라이언트 등록을 Passport 회원 셀프서비스로. 본 ADR이 이 모델 위에 라우트 등록을 흡수.
- [ADR-0016](./0016-dynamic-gateway-routing-reintroduction.md) — 동적 DB 기반 Gateway 라우팅. 본 ADR의 `service_route` 등록 대상.
- [ADR-0017](./0017-gateway-tokenless-passthrough.md) — 게이트웨이 tokenless passthrough 인증 모델.
- `docs/CLIENT_REGISTRATION.md` — 클라이언트(+라우트) 셀프 등록 운영 가이드
- `docs/DYNAMIC_ROUTING.md` — 라우트 등록 경로(어드민 / 회원 셀프) 및 네임스페이스 규칙
- `services/libs/service-client/src/main/java/com/econo/auth/client/application/service/RegisterOAuthClientService.java` — `selfRegister`(라우트 흡수)
- `db/migration/V11__add_owner_id_to_service_route.sql`, `db/migration/V12__add_indexes_to_service_route.sql`
