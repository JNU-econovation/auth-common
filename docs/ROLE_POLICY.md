# 역할(Role) 정책

> 이 문서는 auth-api의 역할 체계와 각 역할의 권한 범위를 정의합니다.
> 신규 기능 추가 시 이 문서를 먼저 참고하고, 변경이 필요하면 이 문서도 함께 업데이트합니다.

---

## 역할 계층

```
SUPER_ADMIN
    └── ADMIN
            └── USER
```

상위 역할은 하위 역할의 권한을 모두 포함합니다.

---

## 역할 정의

| 역할 | 설명 | 부여 방법 |
|------|------|-----------|
| `USER` | 일반 회원. 기본값. | 회원가입 시 자동 부여 |
| `ADMIN` | 어드민 기능 사용 가능. 역할 관리 권한 없음. | SUPER_ADMIN이 어드민 UI에서 부여 |
| `SUPER_ADMIN` | 역할 관리 포함 모든 어드민 기능 사용 가능. | SUPER_ADMIN이 어드민 UI에서 부여 또는 CLI Bootstrap |

---

## 권한 매트릭스

| 기능 | USER | ADMIN | SUPER_ADMIN |
|------|:----:|:-----:|:-----------:|
| 일반 API 사용 | ✅ | ✅ | ✅ |
| 클라이언트 등록 (`POST /api/v1/admin/clients`) | ❌ | ✅ | ✅ |
| 클라이언트 관리 (`GET/POST/DELETE/PUT /api/v1/admin/clients/**`) | ❌ | ✅ | ✅ |
| 회원 목록 조회 (`GET /api/v1/admin/members`) | ❌ | ✅ | ✅ |
| 역할 변경 (`PATCH /api/v1/admin/members/{id}/role`) | ❌ | ❌ | ✅ |

---

## 역할 변경 정책

### 허용

- SUPER_ADMIN은 다른 회원에게 `USER`, `ADMIN`, `SUPER_ADMIN` 역할을 부여/회수할 수 있다.

### 금지 (에러 코드)

| 상황 | 에러 코드 | HTTP |
|------|-----------|------|
| ADMIN이 역할 변경 시도 | `FORBIDDEN` | 403 |
| SUPER_ADMIN이 본인 역할 변경 시도 | `FORBIDDEN_SELF_ROLE_CHANGE` | 403 |
| 마지막 SUPER_ADMIN 해제 시도 | `LAST_SUPER_ADMIN_CANNOT_BE_DEMOTED` | 409 |
| 유효하지 않은 역할값 | `INVALID_ROLE` | 400 |

### 유효 역할 값

`USER`, `ADMIN`, `SUPER_ADMIN` (대소문자 무관, 서버에서 대문자로 정규화)

---

## 역할 변경 즉시 반영 여부

**역할 변경은 해당 회원이 재로그인하기 전까지 JWT에 반영되지 않습니다.**

JWT는 로그인 시점에 발급되며, 이후 역할이 변경되어도 기존 JWT가 만료될 때까지 변경 전 역할이 유지됩니다.

- AT 기본 만료: 1시간
- 즉각 반영이 필요하면 해당 회원에게 재로그인을 안내해야 합니다.

---

## 최초 SUPER_ADMIN 부여 (Bootstrap)

어드민 UI 접근 전, 첫 번째 SUPER_ADMIN은 CLI로 직접 부여해야 합니다.

```bash
# 1. 회원가입 후 memberId 확인
# 2. 역할 부여
curl -X PUT https://auth-api.econovation.kr/api/v1/internal/members/{memberId}/role \
  -H "X-Internal-Api-Key: {AUTH_INTERNAL_API_KEY}" \
  -H "Content-Type: application/json" \
  -d '{"role": "SUPER_ADMIN"}'
```

이후 역할 관리는 어드민 UI에서 진행합니다.

---

## 관련 ADR

- [ADR-0011](adr/0011-admin-ui-passport-role-auth.md) — 어드민 UI 인증 방식 결정
- [ADR-0012](adr/0012-super-admin-role-hierarchy.md) — SUPER_ADMIN 역할 계층 도입
