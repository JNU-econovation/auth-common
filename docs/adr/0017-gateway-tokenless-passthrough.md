# ADR-0017: 게이트웨이 tokenless passthrough — 미토큰 라우팅 허용, 인증 강제를 다운스트림으로 이전

- **상태:** Accepted
- **결정일:** 2026-06-14
- **결정자:** econovation 개발팀
- **관련:** [ADR-0002](./0002-gateway-as-auth-boundary.md) 보완 (Supersede 아님), [ADR-0007](./0007-x-user-passport-header-pattern.md), [ADR-0016](./0016-dynamic-gateway-routing-reintroduction.md)

---

## 배경

`BearerToPassportFilter`는 `@Order(-1)`로 `RouteToRequestUrlFilter`(order=10000)보다 먼저 실행된다. 즉, Spring Cloud Gateway가 경로 prefix를 재작성하기 **전** 원시 인바운드 경로를 본다.

ADR-0016(동적 라우팅 재도입)으로 서비스 라우트를 `service_route` 테이블에서 관리하게 되면서, 서비스들은 `/eeos/**` 형태의 prefix를 가지게 됐다. 예를 들어 EEOS 서비스의 게스트 경로는 `/eeos/guest/…`인데, `application.yml`의 `permitted-paths`는 `/api/guest/**` 형태로 선언되어 있다(접두어 재작성 후 다운스트림 경로 기준으로 선언되어 있어, 게이트웨이 인바운드 원시 경로 `/eeos/guest/…`와 직접 매칭되지 않는다). `BearerToPassportFilter`가 prefix 재작성 전 경로를 보기 때문에 `/eeos/guest/…`는 `permitted-paths` 패턴과 불일치하고, 미토큰 요청이 **오(誤) 401**로 거부된다.

`permitted-paths`를 서비스별로 열거하는 방식으로 우회할 수 있으나, 서비스가 추가될 때마다 게이트웨이를 재배포해야 하는 운영 부담이 다시 생기고 동적 라우팅의 장점이 사라진다.

---

## 결정

**게이트웨이 인증 경계를 "enrich(Passport 주입) + 무효 토큰 거부"로 좁힌다. 미토큰 요청은 경로 무관 passthrough한다.**

구체적으로 `BearerToPassportFilter`의 동작을 다음과 같이 변경한다.

| 분기 | 변경 전 동작 | 변경 후 동작 |
|------|------------|------------|
| 항상 | (없음) | 인바운드 `X-User-Passport` **무조건 제거** (strippedExchange 생성) |
| 미토큰 | `isProtectedPath` → true면 게이트웨이 401, false면 통과 | `isProtectedPath` 체크 **제거** → 무조건 passthrough |
| 유효 토큰 | exchange에 Passport 주입 | strippedExchange 기반으로 검증된 Passport 주입 |
| 무효 토큰 | `isProtectedPath` → true면 401, false면 통과 | **기존과 동일** (strippedExchange 기반으로) |

**인증 강제(미토큰 401 거부)는 다운스트림 `@PassportAuth`(econo-passport)에 위임한다.**

---

## 근거

### 동적 라우팅과 permitted-paths 불일치 해소

게이트웨이가 prefix 재작성 전 경로를 보는 한, `permitted-paths`로 동적 서비스의 공개 경로를 완전히 열거하는 것은 불가능하다. 다운스트림 `@PassportAuth(required = false)` 또는 `@PassportAuth` 없는 엔드포인트가 이미 공개 접근 여부를 결정하므로, 게이트웨이의 미토큰 401 게이트 역할은 중복이다.

### 인바운드 위조 방지 강화

기존에는 미토큰 요청이 보호 경로에서 401로 거부되므로 인바운드 `X-User-Passport` 위조 문제가 수면 위로 드러나지 않았다. passthrough 도입으로 미토큰 요청이 다운스트림에 도달하므로, **인바운드 `X-User-Passport`를 항상 제거**하는 strip 로직을 모든 분기의 최우선 단계로 명시적으로 적용한다. 이로써 위조 헤더를 지닌 미토큰 요청이 다운스트림에서 Passport 보유자로 오인되는 경로를 차단한다.

### 무효 토큰 분기는 유지

헤더에 Bearer 토큰이 **존재하지만 검증에 실패**한 요청은 여전히 보호 경로에서 401로 거부한다. 이는 손상·만료·서명 오류 토큰을 지닌 요청이 다운스트림까지 도달하지 않도록 하는 최소한의 게이트 역할이다.

---

## 트레이드오프

### 긍정적

- 동적 서비스의 공개 경로가 prefix 불일치로 오 401되는 문제 구조적 해소.
- 신규 서비스 온보딩 시 `permitted-paths` 열거 불필요 — 게이트웨이 재배포 없음.
- 인바운드 strip이 명시적으로 모든 경로·모든 분기에 적용되어 위조 방지 보장이 강해짐.

### 주의 / 전제

- 다운스트림 서비스가 `@PassportAuth`로 인증을 강제해야 한다는 전제가 필수다. 다운스트림에서 `@PassportAuth`를 누락하면 미인증 요청이 처리된다.
- 인증이 필요한 다운스트림 서비스는 반드시 `econo-passport`를 의존하고 `@PassportAuth(required = true)` (또는 기본값)를 적용해야 한다.

---

## 보안

- **인바운드 X-User-Passport strip**: 게이트웨이만 `X-User-Passport` 헤더를 신뢰값으로 설정할 수 있다. 인바운드 위조 헤더는 토큰 유무·경로에 관계없이 항상 제거한다.
- **다운스트림 비공개 네트워크 전제**: 내부 서비스는 게이트웨이 뒤에서만 실행된다(ADR-0002). 직접 공개 시 `X-User-Passport` 없는 요청이 다운스트림에 도달한다.
- **무효 토큰 401 유지**: 존재하지만 유효하지 않은 Bearer 토큰은 보호 경로에서 여전히 401로 거부된다.

---

## ADR-0002(게이트웨이 = 인증 경계)와의 관계

이 결정은 ADR-0002를 **supersede하지 않는다**.

ADR-0002의 핵심 원칙("Gateway가 JWT를 검증하고 X-User-Passport 헤더를 내부 서비스에 주입한다")은 그대로 유지된다. 이번 변경은 그 중 "미토큰 요청을 게이트웨이가 401로 거부한다"는 암묵적 역할만 철회하는 보완이다. JWT가 존재하는 한 RS256/만료/issuer 검증 → Passport 주입의 책임은 게이트웨이에 남는다.

---

## 결과

- `permitted-paths`의 역할이 "**무효 토큰 요청의 통과/거부 분기 전용**"으로 축소된다. 미토큰 요청에서는 `permitted-paths`를 참조하지 않는다.
- 미토큰 요청의 인증 강제(401 거부)는 다운스트림 `@PassportAuth` 책임이다.
- 인바운드 `X-User-Passport` strip이 모든 요청의 최우선 단계로 보장된다.

---

## 관련 파일

- `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/config/security/BearerToPassportFilter.java` — 변경 구현체
- `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/config/GatewayRoutingConfig.java` — `permittedPaths()` 역할 축소
- `services/apis/api-gateway/src/main/resources/application.yml` — `gateway.permitted-paths` 용도 변경
