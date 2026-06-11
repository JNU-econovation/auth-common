# ADR-0013: 클라이언트 등록을 Passport 회원 셀프서비스 모델로

- **상태:** Accepted
- **결정일:** 2026-06-12
- **결정자:** econovation 개발팀
- **Supersedes:** [ADR-0010](./0010-client-secret-self-service-auth.md)

---

## 배경

ADR-0010은 "등록은 public(인증 불필요) + redirect-uri 관리는 clientSecret Basic Auth"라는 모델을 채택했다. 이 모델은 당시 SAS가 없는 상황에서 운영팀 친화적 단순화를 목표로 설계되었다.

이후 두 가지 상황이 변화했다.

**첫째, SAS + PKCE 체계가 재도입되었다.** ADR-0011에서 어드민 API 인증이 JWT + Passport ADMIN role 체계로 전환되면서, Gateway가 주입하는 `X-User-Passport` 헤더가 실질적인 인증 수단으로 확립되었다. 이를 통해 클라이언트 등록에도 Passport 기반 인증을 적용할 수 있게 되었다.

**둘째, 클라이언트 등록을 운영팀만이 아닌 인증된 에코노 회원 누구나 할 수 있어야 한다는 요구사항이 생겼다.** "public 등록(ADR-0010의 X2안)"은 인증 없이 누구나 등록할 수 있어 도배 공격에 무방비하고, 등록 주체를 특정할 수 없다는 한계가 있다.

ADR-0010에서 "clientSecret을 발급하면서 소비처가 없다"고 지적된 문제도 해결이 필요했다. 셀프 등록 모델에서는 clientSecret을 발급·보관하되, 현재 in-scope 소비처는 없고 향후 redirect-uri 셀프관리 등에서 사용을 예정한다.

---

## 결정

**SSO 클라이언트 등록을 "인증된 에코노 회원 셀프서비스" 모델로 도입한다.**

구체적으로:

1. **`POST /api/v1/clients` (셀프 등록)** 를 신설한다. Gateway가 주입하는 `X-User-Passport` 헤더에서 `memberId`를 추출해 인증한다. ADMIN 역할 불필요 — 인증된 회원이면 모두 허용.
2. **1인 최대 5개 제한** — `service_client.owner_id` 기반 `countByOwnerId` 조회로 강제.
3. **`clientSecret` 정책 (B안)** — 등록 응답에 평문 1회 노출. 저장은 `service_client.client_secret_hash` BCrypt(cost=12) 해시. SAS `oauth2_registered_client`에는 저장하지 않는다(SAS는 `ClientAuthenticationMethod.NONE` 유지). 현재 clientSecret을 소비하는 in-scope 엔드포인트는 없다 — 향후 redirect-uri 셀프관리 등에서 사용 예정인 선발급·보관 성격.
4. **`service_client` 스키마 변경** — `owner_id BIGINT NULL`(소유자 회원 ID, 셀프 등록 시 설정), `client_secret_hash VARCHAR(72) NULL`(BCrypt 해시) 컬럼 추가. V7 마이그레이션 적용.
5. **기존 `POST /api/v1/admin/clients` (어드민 등록)** 는 ADMIN/SUPER_ADMIN role 전용으로 그대로 유지. 이 경로로 등록된 클라이언트는 `owner_id=NULL`, `client_secret_hash=NULL`.

### 인증 매트릭스

| 엔드포인트 | 인증 | 대상 |
|-----------|------|------|
| `POST /api/v1/clients` | X-User-Passport (memberId 존재 필수) | 인증된 에코노 회원 누구나 |
| `POST /api/v1/admin/clients` | X-User-Passport ADMIN role | 관리자 |
| `GET /api/v1/admin/clients/{id}` 외 redirectUri CRUD 4개 | X-User-Passport ADMIN role | 관리자 |

---

## 근거

### 대안 비교

| 방안 | 등록 인증 | 등록 주체 식별 | 도배 방어 |
|------|-----------|--------------|-----------|
| **A안 — public 등록 (ADR-0010 X2)** | 없음 | 불가 | 없음 (rate limit 필요) |
| **B안 — Passport 셀프서비스 (채택)** | Passport memberId | ownerId 저장 | Gateway JWT 검증으로 자연 차단 |
| C안 — ADMIN만 | ADMIN role | N/A | 완전 차단 |

### B안을 채택한 이유

1. **Gateway JWT 검증이 자연스러운 인증 장벽이 된다.** 비인증 요청은 Gateway에서 이미 차단되므로, public 등록(A안)의 도배 위험을 구조적으로 제거한다.
2. **소유자 추적이 가능하다.** `owner_id`를 통해 누가 어떤 클라이언트를 등록했는지 식별할 수 있고, 1인 5개 제한을 강제할 수 있다.
3. **X-User-Passport 인증 모델과 일관성.** ADR-0011에서 확립된 Passport 기반 인증을 동일하게 적용한다. 별도 인증 채널 불필요.
4. **ADMIN 전용(C안)이 아닌 이유.** 팀원 누구나 자기 서비스를 직접 등록할 수 있어야 한다는 운영 목표에 부합한다.

### clientSecret B안을 선택한 이유

SAS 클라이언트는 `ClientAuthenticationMethod.NONE`(public client) 모델을 유지한다. SAS 표준 `/oauth2/token` 엔드포인트에서 clientSecret을 소비하는 흐름이 현재 범위에 없다. 따라서 clientSecret은 SAS에 저장하지 않고 `service_client`에만 보관하되, 향후 redirect-uri 셀프관리 등 B2B 시나리오에서 사용할 수 있도록 선발급한다.

---

## 결과

### 긍정적 영향

- 인증된 에코노 회원이 관리자 개입 없이 SSO 클라이언트를 자율 등록할 수 있다.
- Gateway JWT 검증이 등록 요청의 1차 인증 장벽 역할을 한다.
- `owner_id`로 등록 주체가 추적 가능하고, 1인 5개 제한으로 DB 폭발을 방어한다.

### 제약 사항 / 주의사항

- ⚠️ **1인 5개 제한에 레이스 조건이 있다.** `countByOwnerId → save` 사이에 동시 요청이 들어오면 극히 드물게 5개 초과 저장이 가능하다. 향후 `SELECT FOR UPDATE` 또는 분산 락으로 강화할 수 있다.
- ⚠️ **clientSecret 폐기·회전 절차가 없다.** 유출 시 클라이언트를 통째로 재등록해야 한다. 필요해지면 별도 rotate-secret 엔드포인트 신설 검토.
- ⚠️ **현재 clientSecret 소비처가 없다.** 등록 시 발급·보관하지만 이를 사용하는 엔드포인트가 현재는 없다. 향후 redirect-uri 셀프관리 API 도입 시 활성화 예정.
- `owner_id` 컬럼이 NULL 허용이므로 어드민 등록 클라이언트와 셀프 등록 클라이언트를 `owner_id IS NULL / IS NOT NULL`로 구분할 수 있다.

### 재검토 조건

- 1인 5개 제한이 과도하거나 부족하다고 판단될 때.
- clientSecret을 실제로 소비하는 엔드포인트(redirect-uri 셀프관리 등)가 도입될 때 — 해당 시점에 소비 흐름 설계를 함께 재검토.
- SAS `ClientAuthenticationMethod.NONE` 모델이 아닌 confidential client 모델로 전환이 필요할 때 — SAS `/oauth2/token` + clientSecret 검증 흐름을 추가해야 하며 이 ADR의 B안이 supersede됨.

---

## 관련 문서

- [ADR-0001](./0001-cookie-based-sso-over-pkce.md) — 쿠키 기반 SSO. 로그인·토큰 발급의 상위 결정.
- [ADR-0010](./0010-client-secret-self-service-auth.md) — **Superseded by this ADR.** public 등록 + Basic Auth 모델. 본 ADR이 Passport 셀프서비스 모델로 대체.
- [ADR-0011](./0011-admin-ui-passport-role-auth.md) — 어드민 API 인증을 Passport ADMIN role로 전환. 본 ADR의 인증 모델 기반.
- `services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/in/web/ClientController.java` — 셀프 등록 컨트롤러
- `services/libs/service-client/src/main/java/com/econo/auth/client/application/usecase/RegisterOAuthClientService.java` — `selfRegister` 메서드
- `services/libs/member/src/main/resources/db/migration/V7__add_owner_id_to_service_client.sql` — owner_id, client_secret_hash 컬럼 추가 마이그레이션
- [docs/CLIENT_REGISTRATION.md](../CLIENT_REGISTRATION.md) — 클라이언트 등록 운영 가이드
