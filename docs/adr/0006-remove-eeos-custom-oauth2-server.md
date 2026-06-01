# ADR-0006: EEOS-BE 자체 OAuth2 서버 제거, auth-api로 위임

- **상태:** Accepted
- **결정일:** 2026-06-01
- **결정자:** econovation 개발팀

---

## 배경

EEOS-BE가 자체 OAuth2 Authorization Server(`ClientController`, `OAuth2Controller`, `TokenExchangeService` 등)를 운영하고 있었다. auth-api 도입으로 두 OAuth2 서버가 공존하는 상황이 발생했다.

---

## 결정

**EEOS-BE의 자체 OAuth2 서버 기능 전체를 제거하고 auth-api로 위임한다.**

제거 대상: `ClientController`, `ClientService`, `ClientEntity`, `OAuth2Controller`, `OAuth2LoginService`, `TokenExchangeService`, `AuthorizationCodeRepository`, `PkceValidator` 등 22개 파일.

DB: `V1.00.0.8__drop_oauth_client_tables.sql` — `oauth_client`, `oauth_client_redirect_uri` 테이블 DROP.

---

## 근거

1. **중복 제거**: 두 곳에서 OAuth2 토큰을 발급하면 어느 토큰이 유효한지 혼란
2. **단일 진실 원천**: 클라이언트 등록/관리는 auth-api에서만
3. **EEOS-BE 단순화**: 비즈니스 로직에만 집중, 인증 인프라 불필요

---

## 결과

- EEOS-BE는 자체 로그인(`POST /api/auth/login`)은 유지 (하위 호환)
- 클라이언트 등록: auth-api `POST /api/v1/admin/clients`로 이전
- `oauth_client` 테이블: 마이그레이션으로 DROP (운영 DB 적용 전 데이터 백업 필요)
