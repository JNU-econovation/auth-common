# ADR-0007: X-User-Passport 헤더로 서비스 간 사용자 정보 전달

- **상태:** Accepted
- **결정일:** 2026-06-01
- **결정자:** econovation 개발팀

---

## 배경

Gateway가 JWT 검증 후 내부 서비스에 사용자 정보를 전달하는 방법이 필요했다.

---

## 결정

**Gateway가 `X-User-Passport: Base64(JSON)` 헤더를 내부 서비스에 주입한다.**

```
X-User-Passport: eyJtZW1iZXJJZCI6MSwibG9naW5JZC...
```

디코딩 값:
```json
{
  "memberId": 1,
  "loginId": "user01",
  "name": "홍길동",
  "generation": 30,
  "status": "AM",
  "roles": ["USER"],
  "issuedAt": "2026-06-01T10:00:00",
  "expiresAt": "2026-06-01T11:00:00"
}
```

내부 서비스는 `econo-passport` 라이브러리의 `@PassportAuth Passport passport`로 주입받는다.

---

## 근거

### 대안 비교

| | X-User-Passport 헤더 (채택) | JWT 그대로 전달 | DB 조회 |
|--|--|--|--|
| 내부 서비스 복잡도 | 낮음 (파싱만) | 높음 (JWT 라이브러리) | 높음 (DB 조회) |
| 성능 | 높음 | 높음 | 낮음 |
| 보안 | Gateway 내부망에서만 신뢰 | 서비스마다 서명 검증 | 안전 |

### Base64 JSON을 선택한 이유

- JSON → 타입 안전, 필드 추가 용이
- Base64 인코딩 → HTTP 헤더 특수문자 문제 없음
- 별도 JWT 라이브러리 불필요 (단순 Base64 디코딩)

---

## 결과

- **보안 전제**: 내부 서비스가 직접 공개되면 X-User-Passport 위조 가능 → 네트워크 정책 필수
- Gateway를 통하지 않는 직접 호출 시 PassportAuthenticationFilter가 헤더 없음을 감지 → 인증 실패
- `removeRequestHeader("Authorization")`: EEOS-BE 자체 HMAC 검증기와 충돌 방지 목적으로 Gateway에서 제거

---

## 관련 문서

- [ADR-0002](./0002-gateway-as-auth-boundary.md)
- [econo-passport](https://github.com/JNU-econovation/econo-passport)
