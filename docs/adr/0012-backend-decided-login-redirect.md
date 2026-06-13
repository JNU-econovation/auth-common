# ADR-0012: 로그인 성공 후 리다이렉트를 백엔드가 clientId로 결정

- **상태:** Accepted
- **결정일:** 2026-06-07
- **결정자:** econovation 개발팀

---

## 배경

자격증명 기반 로그인 엔드포인트(`POST /api/v1/auth/login`, `JsonLoginAuthenticationFilter`)는 인증 성공 시 토큰을 발급하고 `200 OK` + `Set-Cookie`만 반환한다. 로그인 이후 사용자를 어디로 보낼지(원래 가려던 서비스)는 **프론트엔드 JavaScript가 결정·수행**하고 있었다.

이 구조에는 open redirect / 리다이렉트 변조 위험이 있다. 프론트엔드가 리다이렉트 목적지를 검증 없이 따라가면, 공격자가 로그인 직후 사용자를 임의의 외부 사이트로 유도해 피싱할 수 있다. 검증 책임이 신뢰할 수 없는 클라이언트 측에 있었다.

이 엔드포인트(자격증명 직접 검증 → 토큰 직접 발급, 이하 "경로 A")는 ADR-0001의 쿠키 기반 SSO 결정을 따르는 1차 로그인 경로로 유지한다. 별도로 존재하는 SAS 기반 `/oauth2/authorize`(Authorization Code + PKCE, "경로 B")는 이번 변경 대상이 아니다.

설계 초기에는 클라이언트가 목적지 URL(`returnUrl`)을 보내고 백엔드가 등록 origin 화이트리스트로 검증하는 방안을 검토했으나, 더 단순하고 구조적으로 안전한 방식이 있음을 확인했다(아래 근거).

---

## 결정

**경로 A의 로그인 요청은 목적지 URL이 아니라 `clientId`만 받고, 백엔드가 그 clientId에 등록된 redirect_uri를 조회해 그곳으로 직접 `302`한다.**

- `POST /api/v1/auth/login`의 JSON body가 `clientId`를 받는다. (`{loginId, password, clientId}` — `clientId`는 선택 필드)
  - 로그인 필터가 `attemptAuthentication`에서 body InputStream을 한 번만 소비하므로, `clientId`도 그 시점에 함께 파싱해 `request.setAttribute`로 `successfulAuthentication`에 전달한다.
- **WEB 클라이언트**(`Client-Type` 헤더가 `APP`이 아닐 때): 자격증명 검증 → 토큰 발급 → HttpOnly 쿠키(`at`, `rt`) 세팅 → `clientId`로 등록 redirect_uri 조회 → 그 URL로 `302 redirect`.
- **fallback**: `clientId`가 없거나 / 미등록(존재하지 않는 clientId) / 등록 redirect_uri가 없으면 **요청을 거부하지 않고** 안전한 기본 URL(`auth.redirect.default-url`)로 `302`. (미등록은 인증 실패 `4xx`가 아니라 fallback으로 처리)
- **조회**: 기존 `ClientRedirectUriService.findByClientId(clientId)`를 읽기 전용으로 재사용한다(의존 방향: auth-api → service-client). 미존재 시 던지는 `InvalidClientException`은 신규 `LoginRedirectResolver`가 잡아 기본 URL로 fallback한다.
- **복수 redirect_uri**: 한 클라이언트에 redirect_uri가 여러 개면 "첫 번째(대표)"를 사용한다. 단 SAS `RegisteredClient`는 redirect_uri를 **순서 비보장 `Set`**으로 저장하므로, 결정적 기준(**정렬 후 첫 번째**)으로 선택한다. redirect_uri가 1개면 그것을 사용한다.
- **APP 클라이언트**(`Client-Type: APP`): 기존 동작 그대로 유지(`200 OK` + body에 AT/RT, 리다이렉트 없음).
- **토큰은 절대 리다이렉트 URL(query/fragment)에 싣지 않는다.** WEB은 쿠키 전용.

---

## 근거

### 리다이렉트 결정 방식 비교

| 방식 | 클라이언트가 보내는 것 | open redirect 방어 | 비고 |
|------|----------------------|-------------------|------|
| 프론트 리다이렉트 (기존) | (프론트가 자체 판단) | 클라이언트 신뢰 필요 (취약) | 검증 누락 시 무방비 |
| returnUrl + 화이트리스트 검증 | 목적지 URL | origin 화이트리스트로 차단 | user-supplied URL을 매번 검증해야 함 (검증 실수 여지) |
| **clientId → 등록 redirect_uri (채택)** | clientId | **구조적 불가능** | user-supplied URL이 없음 |

### clientId 방식이 맞는 이유

1. **user-supplied URL이 존재하지 않는다.** 클라이언트는 식별자(`clientId`)만 보내고 목적지 URL은 백엔드가 등록 정보에서 결정한다. 검증해야 할 임의 URL 자체가 없으므로 open redirect가 **구조적으로 불가능**하다 — 검증 로직의 실수·우회 가능성을 원천 제거한다.
2. **단일 소스를 그대로 신뢰한다.** 허용 목적지는 이미 등록 클라이언트의 redirect_uri로 관리되고 있다. `ClientRedirectUriService.findByClientId`를 재사용하므로 별도 화이트리스트나 origin 추출 로직이 필요 없다.
3. **토큰을 URL에 싣지 않는다.** 토큰은 HttpOnly 쿠키로만 전달(ADR-0001)되고 리다이렉트 URL에는 어떤 비밀도 담기지 않는다.
4. **실패 시 거부가 아닌 안전한 기본값.** clientId 누락·미등록을 에러로 처리하면 정상 사용자가 막힐 수 있어, 안전한 기본 URL로 보내 가용성과 보안을 모두 확보한다.

### 대안과 제거 이유

- **returnUrl + origin 화이트리스트 검증** — 클라이언트가 목적지 URL을 보내고 백엔드가 등록 origin 집합과 대조하는 방식. 동작하지만 user-supplied URL을 매 요청 검증해야 하고(검증 누락·파싱 우회 여지), open redirect 표면이 남는다. clientId 방식이 그 표면을 아예 없애므로 제거. (이 ADR의 초기 검토안이었음)
- **프론트엔드 검증 강화** — 검증 기준(등록 redirect_uri)이 백엔드에 있고 클라이언트 코드 변조/누락에 취약해 신뢰 경계 위반. 제거.
- **경로 A 폐기 후 경로 B(OAuth Code+PKCE)로 일원화** — 가장 표준적이나 ADR-0001의 쿠키 기반 SSO 방향과 충돌하고 변경 범위가 큼. 이번에는 경로 A 유지를 전제로 함(아래 재검토 조건).

---

## 결과

### 긍정적 영향

- 로그인 직후 open redirect / 리다이렉트 변조를 **구조적으로 차단**(검증 대상 URL 자체가 없음)
- 허용 목적지가 등록 클라이언트 redirect_uri와 단일 소스로 일치, 별도 화이트리스트 불필요
- 토큰은 쿠키 전용 유지 → 리다이렉트 URL 노출과 무관하게 비밀 보호

### 제약 사항 / 주의사항

- **복수 redirect_uri의 "대표" 선택이 등록 순서를 반영하지 못한다.** SAS `RegisteredClient`가 redirect_uri를 순서 비보장 `Set`(`oauth2_registered_client.redirect_uris`는 `VARCHAR(1000)` 쉼표 구분 문자열)으로 저장하므로, 정렬 후 첫 번째라는 결정적이지만 임의적인 기준을 쓴다. 로그인 후 특정 랜딩 URL이 중요한 클라이언트는 **redirect_uri를 1개만 등록**하는 것을 권장한다. 명시적 "primary redirect_uri" 개념이 필요해지면 별도 보조 테이블/플래그 도입을 검토한다(이번 범위 밖).
- **이번 범위에서 `SameSite=None` 인증 쿠키의 CSRF 대응은 제외**한다. 별도 이슈로 추적한다.
- 쿠키 세팅(`addHeader`) 후 `response.sendRedirect()`를 호출해야 한다. `sendRedirect`가 응답을 커밋하므로 순서가 바뀌면 `Set-Cookie`가 누락된다.
- 신규 설정 키 `auth.redirect.default-url`은 기존 `auth.frontend-login-url`(SAS 미인증 진입 리다이렉트 용도)과 **역할이 다르므로 분리**한다. 환경별(스테이징/프로덕션) 실제 값은 배포 설정에서 관리한다.
- 미등록 `clientId`(`InvalidClientException`)는 인증 실패가 아니라 기본 URL fallback으로 처리된다. 클라이언트 입장에서 "잘못된 clientId"를 명시적으로 알 수 없다(의도된 동작 — 로그인 자체는 성공시키되 안전한 기본 목적지로 보냄).

### 재검토 조건

- ADR-0001의 쿠키 기반 SSO를 벗어나 자사 프런트엔드도 OAuth Authorization Code + PKCE로 일원화하기로 하면(경로 A 폐기), 이 결정은 그 ADR로 supersede한다.
- 복수 redirect_uri 중 등록 순서/명시적 대표 지정이 요구사항이 되면 "primary redirect_uri" 도입과 함께 재검토한다.

---

## 관련 문서

- [ADR-0001](./0001-cookie-based-sso-over-pkce.md) — 쿠키 기반 SSO (경로 A의 토큰 전달 방식 근거)
- [ADR-0002](./0002-gateway-as-auth-boundary.md) — Gateway를 인증 경계로 사용하는 결정
- [.claude/plans/07-backend-decided-login-redirect/](../../.claude/plans/07-backend-decided-login-redirect/) — 본 결정의 todo / API / 구현 / DB 설계 plan
