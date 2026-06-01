# ADR-0009: 회원 일괄 조회 API — POST /batch + body 방식 채택

- **상태:** Accepted
- **결정일:** 2026-06-01
- **결정자:** econovation 개발팀

---

## 배경

내부 서비스(EEOS-BE 등)가 타 회원의 이름·기수·상태를 조회해야 하는 케이스가 있다. Passport는 요청자 자신의 정보만 담기 때문에, 별도 조회 API가 필요하다.

단건/다건을 어떤 HTTP 메서드와 URL 구조로 설계할지 결정이 필요했다.

---

## 검토한 선택지

| 선택지 | 문제점 |
|--------|--------|
| `GET /members?ids=1,2,3` | URL 길이 제한(서버·프록시마다 2KB~8KB). IDs 수십 개 초과 시 위험 |
| `GET /members/{id}` (단건만) | 다건 조회 시 N번 왕복 |
| `GET /members` with body | RFC 7231/9110: "GET body는 의미 미정의". 프록시·CDN이 무시하거나 거부할 수 있음. Elasticsearch가 이 방식을 써서 호환성 문제 발생한 전례 있음 |
| `POST /members/batch` | **채택** |

---

## 결정

**`POST /api/v1/members/batch`에 body로 IDs 배열을 전달하는 방식을 사용한다.**

```
POST /api/v1/members/batch
Content-Type: application/json

{ "ids": [1, 2, 42] }
```

단건도 배열에 하나만 담아 동일 엔드포인트를 사용한다.

---

## 근거

1. **URL 길이 제한 없음**: body에 담으므로 IDs 수에 제한 없음 (서버 설정 한도까지)
2. **업계 표준**: Facebook Batch API, Google Cloud Batch API 모두 `POST /batch` + body 방식 사용
3. **단건/다건 통합**: 클라이언트가 단건/다건을 구분할 필요 없어 API 표면적 감소
4. **/batch 명명**: `/query`(동사형, RPC 느낌)보다 `/batch`(명사형, 의도 명확)가 REST 관례에 적합

### 미래 고려: HTTP QUERY 메서드

IETF HTTPbis WG이 `QUERY` 메서드를 표준화 진행 중 (2026년 5월 Rev.14).
safe+idempotent이면서 body를 허용해 GET+POST 양쪽 한계를 해결한다.
브라우저·서버 지원이 완성되면 `QUERY /members/batch`로 전환 고려.

---

## 결과

- 최대 1,000개 IDs 제한 (`@Size` 검증)
- 없는 ID는 조용히 제외 (오류 아님), 빈 배열이면 400
- `GET /members/{id}` 단건 엔드포인트는 별도로 두지 않음

---

## 관련 문서

- [ADR-0007](./0007-x-user-passport-header-pattern.md)
- [passport-claims-reference.md](../passport-claims-reference.md)
