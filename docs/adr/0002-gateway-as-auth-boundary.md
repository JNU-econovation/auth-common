# ADR-0002: api-gateway를 인증 경계로 사용

- **상태:** Accepted
- **결정일:** 2026-06-01
- **결정자:** econovation 개발팀

---

## 배경

마이크로서비스 환경에서 JWT 검증을 어느 레이어에서 처리할지 결정이 필요했다.

- **방법 A:** Gateway에서 검증 → 내부 서비스는 X-User-Passport만 신뢰
- **방법 B:** 각 서비스가 직접 JWT 검증
- **방법 C:** 매 요청마다 auth-api에 토큰 검증 요청 (Token Introspection)

---

## 결정

**api-gateway가 JWT를 검증하고 X-User-Passport 헤더를 내부 서비스에 주입한다.**

- Gateway: RS256 서명 검증 (JWKS 캐시), X-User-Passport 주입
- 내부 서비스: X-User-Passport 헤더만 파싱, 별도 JWT 검증 없음
- 내부 서비스는 반드시 Gateway 뒤에서만 실행 (직접 공개 금지)

---

## 근거

| 항목 | Gateway 검증 (채택) | 서비스별 검증 | Token Introspection |
|------|-----------------|------------|-------------------|
| 구현 복잡도 | 낮음 (한 곳만) | 높음 (서비스마다) | 낮음 |
| auth-api 의존성 | 없음 (JWKS 캐시) | 없음 | **매 요청마다 호출** |
| 장애 내성 | 높음 | 높음 | auth-api 장애 시 전체 중단 |
| 서비스 코드 단순성 | 높음 | 낮음 | 높음 |
| 서비스 직접 노출 시 | **취약** | 안전 | 안전 |

Gateway 검증을 선택한 이유:
1. 내부 서비스가 직접 노출되지 않는 구조 → 취약점 제거
2. 각 서비스에 JWT 라이브러리/공개키 배포 불필요
3. JWKS 캐싱으로 auth-api 없이 검증 가능 → 고가용성

---

## 결과

- 내부 서비스는 `econo-passport` 라이브러리만 추가하면 `@PassportAuth`로 사용자 정보 획득
- 새 서비스 추가 시 `/register-service` 스킬 실행으로 표준화된 온보딩
- 내부 서비스 직접 노출 시 인증 우회 가능 → 네트워크 정책으로 Gateway만 허용 필수

---

## 관련 문서

- [ADR-0001](./0001-cookie-based-sso-over-pkce.md) — 쿠키 기반 SSO 결정
- [api-gateway/README.md](../services/apis/api-gateway/README.md)
