# ADR-0011: 어드민 UI 인증 — X-Internal-Api-Key 대신 JWT Passport ADMIN role 체계 도입

- **상태:** Accepted
- **결정일:** 2026-06-04
- **결정자:** econovation 개발팀

---

## 배경

OAuth 클라이언트 등록·관리 API(`POST /api/v1/admin/clients`, redirectUri 관리 4개)의 초기 설계는 `X-Internal-Api-Key` 헤더로 인증했다.

이 방식은 CLI/curl에서는 동작하지만, **브라우저 기반 관리자 UI를 구축할 수 없다**는 근본적인 문제가 있다. API Key가 JS 번들이나 네트워크 탭에 노출되기 때문이다.

팀이 관리자 UI 구축을 요구함에 따라 인증 방식을 전면 재설계했다.

---

## 결정

**어드민 API 인증을 X-Internal-Api-Key에서 JWT + Passport ADMIN role 체계로 전환한다.**

구체적으로:

1. `Member` 도메인에 `role` 필드(`USER` / `ADMIN`) 추가
2. 로그인 시 발급되는 JWT에 `roles: [member.getRole()]` 클레임 포함
3. Gateway가 JWT를 검증하고 `X-User-Passport` 헤더(Base64 JSON)를 주입
4. `AdminClientController`가 `X-User-Passport`의 `roles`에서 `ADMIN` 여부를 확인
5. 최초 관리자 부여는 `PUT /api/v1/internal/members/{id}/role` (X-Internal-Api-Key 보호, CLI 전용)

---

## 근거

| 방식 | 브라우저 UI | 보안 | 구현 복잡도 |
|------|------------|------|------------|
| X-Internal-Api-Key | ❌ Key 노출 | △ 네트워크 탭 노출 위험 | 낮음 |
| 별도 Admin 토큰 발급 | ✅ | ✅ | 높음 (새 인증 흐름) |
| **JWT + ADMIN role** | **✅** | **✅** | **낮음 (기존 흐름 재사용)** |

JWT + ADMIN role이 채택된 이유:
- 로그인 → 쿠키 → Gateway 검증 → Passport 주입 흐름이 이미 구축되어 있다
- `Passport.isAdmin()`, `Roles.ADMIN` 상수가 econo-passport에 이미 정의되어 있었다
- 관리자도 "에코노베이션 회원"이므로 별도 계정 체계가 불필요하다

X-Internal-Api-Key는 **최초 관리자 계정 부여**(Bootstrap)처럼 사람이 직접 개입하는 일회성 CLI 작업에만 남긴다.

---

## 결과

### 긍정적 영향
- 브라우저 어드민 UI에서 `credentials: 'include'` 쿠키만으로 관리자 API 호출 가능
- 권한 체계가 JWT에 통합되어 일관성 확보
- X-Internal-Api-Key를 프론트엔드 코드에 포함할 필요 없음

### 제약 사항 / 주의사항
- **role 변경은 재로그인 전까지 반영되지 않는다.** JWT에 role이 포함되므로, ADMIN 권한을 부여받은 회원은 로그아웃 후 재로그인해야 어드민 API를 사용할 수 있다.
- `PUT /api/v1/internal/members/{id}/role`은 X-Internal-Api-Key로만 접근 가능하다. 최초 관리자가 없는 상태에서 어드민 UI로 관리자를 만들 수 없으므로, 최초 관리자는 반드시 CLI로 부여해야 한다.
- `/api/v1/admin/**` 경로가 Gateway를 통해 외부에 노출되므로, CORS 설정과 JWT 만료 시간 관리를 철저히 해야 한다.

### 재검토 조건
- 관리자 수가 많아지거나 세분화된 권한(부서 관리자 등)이 필요해지면 `Roles.departmentAdmin()`처럼 동적 role 확장 검토
- role 변경의 즉시 반영이 필요해지면 Redis 기반 role 캐시 + 토큰 무효화 도입 검토

---

## 관련 문서

- ADR-0002: Gateway as Auth Boundary
- ADR-0007: X-User-Passport 헤더 패턴
- `Roles.java`, `Passport.isAdmin()` — econo-passport 라이브러리
- `AdminRoleController` — 최초 관리자 부여 CLI API
