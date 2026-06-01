# ADR-0001: 쿠키 기반 SSO — PKCE 대신 HttpOnly 쿠키 방식 채택

- **상태:** Accepted
- **결정일:** 2026-06-01
- **결정자:** econovation 개발팀

---

## 배경

econovation은 여러 서비스(EEOS, Makers, Incubator 등)를 운영하며, 사용자가 한 번 로그인하면 모든 서비스에서 재로그인 없이 이용하는 SSO(Single Sign-On)가 필요하다.

초기에는 OAuth2 표준인 Authorization Code + PKCE 흐름을 구현했으나, 실제 사용 목적을 분석한 결과 더 단순한 방식이 적합함을 확인했다.

---

## 결정

**AT(Access Token)와 RT(Refresh Token) 모두 HttpOnly 쿠키로 발급한다.**

```
Set-Cookie: at=<JWT>; HttpOnly; SameSite=None; Secure; Domain=.econovation.kr; Max-Age=3600
Set-Cookie: rt=<JWT>; HttpOnly; SameSite=None; Secure; Domain=.econovation.kr; Max-Age=2592000
```

모든 서비스는 api-gateway 뒤에 배치되며, Gateway가 at 쿠키 또는 Bearer 헤더에서 JWT를 추출해 검증 후 X-User-Passport 헤더로 내부 서비스에 전달한다.

---

## 근거

### SSO 구현 방식 비교

| 방식 | 적합한 경우 | 복잡도 |
|------|-----------|--------|
| 쿠키 공유 (채택) | 같은 도메인 계열 내부 앱 | 낮음 |
| PKCE | 외부 앱이 econovation 계정을 빌려 쓸 때 | 높음 |
| 세션 공유 | 상태 저장 필요한 경우 | 중간 |

### 쿠키 방식이 맞는 이유

1. **모든 서비스가 Gateway를 통과한다.**
   Gateway에 등록된 서비스만 접근 가능하므로 econovation의 제어 아래에 있다.

2. **모든 서비스가 `*.econovation.kr` 도메인을 사용한다.**
   `Domain=.econovation.kr` 설정으로 `app-a.econovation.kr`, `app-b.econovation.kr` 등 모든 서브도메인에서 쿠키가 자동 전송된다.

3. **`SameSite=None`으로 크로스사이트 요청도 허용된다.**
   `app.startup.com`에서 `gateway.econovation.kr`을 호출해도 at 쿠키가 자동 전송된다. 즉, Gateway를 통과하는 외부 서비스도 별도 PKCE 없이 동작한다.

4. **JWT는 auth-api 없이 로컬 검증 가능하다.**
   RS256 서명을 Gateway가 캐시된 공개키(JWKS)로 검증하므로 매 요청마다 auth-api 호출이 없다. auth-api 장애 시에도 이미 발급된 AT로 서비스 계속 동작.

### PKCE를 제거한 이유

PKCE는 다음 경우에 필요하다:

- **외부 앱이 econovation Gateway를 통하지 않고 econovation 계정만 빌려 쓸 때**
  예: 카카오가 "네이버 아이디로 로그인" 버튼을 달고 싶은 것처럼, 완전히 독립된 외부 서비스가 econovation 계정을 OAuth2 identity provider로 사용할 때.

현재 계획된 모든 서비스는 Gateway를 통과하므로 이 케이스에 해당하지 않는다.

---

## 결과

### 긍정적 영향

- 구현 단순화: PKCE 코드 및 SAS 인가 서버 설정 불필요
- SSO 자동화: `COOKIE_DOMAIN=.econovation.kr` 설정만으로 모든 서브도메인 SSO 적용
- JWT 장점 유지: auth-api 없이 로컬 검증, 발급 서버 장애 내성

### 제약 사항

- `COOKIE_SECURE=true` + `SameSite=None` 조합은 HTTPS 필수 (HTTP에서 작동 안 함)
- 운영 환경에서 `COOKIE_DOMAIN=.econovation.kr` 설정 누락 시 SSO 미동작
- AT가 HttpOnly 쿠키이므로 JavaScript에서 직접 읽기 불가 (의도된 보안)

### PKCE 재활성화 조건

다음 상황이 발생하면 PKCE 도입을 재검토한다:
- econovation Gateway를 통하지 않는 외부 서비스가 econovation 계정 인증을 요구할 때
- 모바일 앱 외 서드파티 앱에 표준 OAuth2 연동을 제공해야 할 때

---

## 관련 문서

- [api-gateway/README.md](../services/apis/api-gateway/README.md) — 쿠키 SSO 흐름 상세
- [auth-api/README.md](../services/apis/auth-api/README.md) — 로그인 API 레퍼런스
- [ADR-0002](./0002-gateway-as-auth-boundary.md) — Gateway를 인증 경계로 사용하는 결정
