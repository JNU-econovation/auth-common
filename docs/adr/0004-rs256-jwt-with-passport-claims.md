# ADR-0004: RS256 JWT에 Passport 클레임 포함

- **상태:** Accepted
- **결정일:** 2026-06-01
- **결정자:** econovation 개발팀

---

## 배경

Gateway가 JWT를 검증한 후 내부 서비스에 사용자 정보를 전달하는 방법이 필요했다.

---

## 결정

**AT(Access Token)는 RS256 서명 JWT이며, Passport 클레임(회원 정보)을 직접 포함한다.**

```json
{
  "sub": "1",
  "memberId": 1,
  "loginId": "user01",
  "name": "홍길동",
  "generation": 30,
  "status": "AM",
  "roles": ["USER"],
  "iss": "https://gateway.econovation.kr",
  "iat": 1780239351,
  "exp": 1780242951
}
```

Gateway는 JWT를 검증하고 위 클레임을 Base64 인코딩하여 `X-User-Passport` 헤더로 내부 서비스에 전달한다.

---

## 근거

### JWT vs Opaque Token

| | JWT (채택) | Opaque Token |
|--|--|--|
| 내부 서비스 정보 접근 | JWT 파싱만으로 즉시 | auth-api에 매번 조회 |
| auth-api 장애 내성 | 높음 | auth-api 없으면 불가 |
| 토큰 즉시 무효화 | 어려움 | 즉시 가능 |

### Passport 클레임을 JWT에 포함하는 이유

- DB 조회 없이 사용자 정보(이름, 기수, 권한 등) 즉시 사용
- Gateway가 한 번 추출 → 모든 내부 서비스에 헤더로 전달
- 내부 서비스에서 memberId로 DB 추가 조회 불필요

### RS256 선택 이유

- 공개키/개인키 분리: auth-api만 서명, Gateway는 검증만
- JWKS 표준으로 공개키 배포 가능
- HMAC(HS256)과 달리 검증자가 서명자가 될 수 없어 위조 불가

---

## 결과

- AT 크기 증가 (클레임 포함으로 ~600-700자)
- 사용자 정보 변경(이름, 권한 등)이 AT 만료 전까지 반영 안 됨 → 1시간 만료 정책으로 허용 범위

---

## 관련 문서

- [ADR-0002](./0002-gateway-as-auth-boundary.md)
