# ADR-0012: SUPER_ADMIN 역할 계층 도입

- **상태:** Accepted
- **결정일:** 2026-06-04
- **결정자:** econovation 개발팀

---

## 배경

ADR-0011에서 어드민 UI를 위해 `ADMIN` 역할을 도입했다. 이후 역할 관리(부여/회수) 기능이 필요해졌는데, **모든 ADMIN이 다른 ADMIN을 임의로 해제할 수 있으면** 시스템 보안이 취약해진다.

- ADMIN A가 ADMIN B를 해제 → B가 A를 해제 → 역할 관리가 혼란스러워짐
- 역할 관리 권한을 가진 더 높은 레벨의 역할이 필요하다

---

## 결정

**`SUPER_ADMIN` 역할을 도입하여 역할 관리 권한을 분리한다.**

역할 계층: `SUPER_ADMIN > ADMIN > USER`

- `ADMIN`: 어드민 기능 사용 (클라이언트 관리, 회원 목록 조회). 역할 변경 불가.
- `SUPER_ADMIN`: ADMIN의 모든 권한 + 역할 변경 권한 (부여/회수).

---

## 근거

| 방식 | 권한 명확성 | 구현 복잡도 | 보안 |
|------|------------|------------|------|
| ADMIN이 모두 관리 | ❌ 혼란 | 낮음 | 취약 |
| 별도 관리자 계정 | △ 별도 인증 필요 | 높음 | 보통 |
| **SUPER_ADMIN 계층** | **✅ 명확** | **낮음** | **안전** |

`Roles.java`에 이미 `SUPER_ADMIN` 상수가 정의되어 있었으므로 추가 라이브러리 변경 없이 적용 가능했다.

---

## 결과

### 긍정적 영향
- ADMIN은 역할 변경 권한이 없어 권한 남용 방지
- SUPER_ADMIN이 1명 이상 유지되도록 강제하여 시스템 잠금 방지
- `Passport.isAdmin()`이 SUPER_ADMIN도 포함하도록 수정하여 하위 호환 유지

### 제약 사항 / 주의사항
- 최초 SUPER_ADMIN은 CLI(`PUT /api/v1/internal/members/{id}/role`)로만 부여 가능
- 역할 변경은 재로그인 전까지 JWT에 반영되지 않음
- SUPER_ADMIN 본인은 자신의 역할을 변경할 수 없음 (다른 SUPER_ADMIN이 변경해야 함)

### 재검토 조건
- 팀 규모가 커져 DEPARTMENT_ADMIN 등 세분화된 권한이 필요해지면 `Roles.departmentAdmin()` 패턴으로 확장

---

## 관련 문서

- ADR-0011: 어드민 UI Passport ADMIN role 인증
- `docs/ROLE_POLICY.md` — 역할 정책 전체 문서
- `AdminMemberController` — 역할 관리 API 구현
