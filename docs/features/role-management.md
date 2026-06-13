# 기능 명세: 역할(Role) 관리

> **상태:** 구현 완료
> **대상 독자:** 기획자, 프론트엔드, 백엔드
> **관련 코드:** `AdminMemberController`, `MemberRepository`, `Member.role`

---

## 개요

에코노베이션 SSO의 회원 역할을 관리하는 기능.
SUPER_ADMIN이 회원에게 ADMIN 역할을 부여하거나 회수한다.

---

## 역할 계층

```
SUPER_ADMIN  ←  역할 관리 가능 (부여/회수)
    │
  ADMIN      ←  어드민 기능 사용 가능 (클라이언트 관리, 회원 목록 조회)
    │
  USER       ←  일반 회원 (기본값)
```

상위 역할은 하위 역할의 권한을 모두 포함한다.

---

## 역할 정의

| 역할 | 설명 | 부여 방법 |
|------|------|-----------|
| `USER` | 일반 회원. 기본값. | 회원가입 시 자동 |
| `ADMIN` | 어드민 기능 사용. 역할 관리 권한 없음. | SUPER_ADMIN이 어드민 UI에서 부여 |
| `SUPER_ADMIN` | 역할 관리 포함 모든 어드민 기능. | SUPER_ADMIN이 어드민 UI에서 부여 또는 CLI Bootstrap |

---

## 권한 매트릭스

| 기능 | USER | ADMIN | SUPER_ADMIN |
|------|:----:|:-----:|:-----------:|
| 일반 서비스 이용 | ✅ | ✅ | ✅ |
| 어드민 UI 접근 | ❌ | ✅ | ✅ |
| 클라이언트 등록/관리 | ❌ | ✅ | ✅ |
| 회원 목록 조회 | ❌ | ✅ | ✅ |
| 역할 변경 (부여/회수) | ❌ | ❌ | ✅ |

---

## API 명세

### 회원 목록 조회

```
GET /api/v1/admin/members?page=0&size=20&role=ADMIN
인증: Bearer JWT (ADMIN 또는 SUPER_ADMIN)
```

**Query Parameters**

| 파라미터 | 기본값 | 설명 |
|---------|--------|------|
| `page` | `0` | 페이지 번호 |
| `size` | `20` | 페이지 크기 |
| `role` | (없음) | 역할 필터. 없으면 전체 조회 |

**Response 200**

```json
{
    "content": [
        {
            "memberId": 1,
            "name": "홍길동",
            "loginId": "honggildong",
            "generation": 30,
            "status": "AM",
            "role": "ADMIN"
        }
    ],
    "totalElements": 50,
    "totalPages": 3,
    "page": 0,
    "size": 20
}
```

---

### 역할 변경

```
PATCH /api/v1/admin/members/{memberId}/role
인증: Bearer JWT (SUPER_ADMIN 전용)
```

**Request Body**

```json
{ "role": "ADMIN" }
```

유효 역할 값: `USER`, `ADMIN`, `SUPER_ADMIN` (대소문자 무관)

**Response 200**

```json
{ "memberId": 10, "role": "ADMIN" }
```

---

## 정책 (비즈니스 규칙)

### 허용
- SUPER_ADMIN은 다른 회원의 역할을 `USER`, `ADMIN`, `SUPER_ADMIN` 중 하나로 변경할 수 있다.

### 금지

| 상황 | 에러 코드 | HTTP |
|------|-----------|------|
| ADMIN이 역할 변경 시도 | `FORBIDDEN` | 403 |
| SUPER_ADMIN이 **본인** 역할 변경 시도 | `FORBIDDEN_SELF_ROLE_CHANGE` | 403 |
| **마지막 SUPER_ADMIN** 해제 시도 | `LAST_SUPER_ADMIN_CANNOT_BE_DEMOTED` | 409 |
| 유효하지 않은 역할 값 (`MANAGER` 등) | `INVALID_ROLE` | 400 |
| 존재하지 않는 회원 | `NOT_FOUND` | 404 |

---

## 역할 변경 즉시 반영 여부

**재로그인 전까지 JWT에 반영되지 않는다.**

JWT는 로그인 시점에 발급되며 만료(기본 1시간)까지 변경 전 역할이 유지된다.
→ 프론트엔드에서 역할 변경 후 "적용까지 최대 1시간이 소요됩니다. 지금 바로 적용하려면 재로그인하세요." 안내 필요.

---

## 화면 흐름 (프론트엔드)

```
[회원 목록]
┌─────────────────────────────────────────────────────┐
│ 필터: [전체 ▼]   검색                                │
├──────┬──────────┬──────┬────┬────────┬─────────────┤
│ 이름  │ loginId  │ 기수 │ 상태│ 역할   │ 액션        │
├──────┼──────────┼──────┼────┼────────┼─────────────┤
│ 홍길동│ hong     │ 30  │ AM │ USER   │ [관리자 지정]│
│ 김에코│ eeco     │ 29  │ AM │ ADMIN  │ [관리자 해제]│
│ 박루비│ ruby     │ 28  │ RM │ SUPER  │ (본인 — 비활)│  ← 본인 행은 비활성화
└──────┴──────────┴──────┴────┴────────┴─────────────┘
```

- "관리자 지정" 클릭 → 확인 다이얼로그 → PATCH 호출
- "관리자 해제" 클릭 → 확인 다이얼로그 → PATCH 호출
- 본인 행: 액션 버튼 비활성화 (서버 측에서도 막지만 UX상 선제적으로)
- 성공 시: "홍길동님을 관리자로 지정했습니다. 적용까지 최대 1시간 소요됩니다."

---

## 최초 SUPER_ADMIN 부여 (Bootstrap)

어드민 UI 이전에 첫 번째 SUPER_ADMIN은 CLI로 직접 부여한다.

```bash
curl -X PUT https://auth-api.econovation.kr/api/v1/internal/members/{memberId}/role \
  -H "X-Internal-Api-Key: {AUTH_INTERNAL_API_KEY}" \
  -H "Content-Type: application/json" \
  -d '{"role": "SUPER_ADMIN"}'
```

이후 역할 관리는 어드민 UI에서 진행한다.
