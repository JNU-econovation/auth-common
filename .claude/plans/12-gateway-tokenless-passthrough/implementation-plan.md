# gateway-tokenless-passthrough - implementation

## 메타
- **작업명**: gateway-tokenless-passthrough
- **문서 타입**: implementation
- **작성일**: 2026-06-14
- **관련 문서** (같은 디렉터리):
  - todo.md

---

## 개요

`api-gateway` 모듈의 `BearerToPassportFilter`(GlobalFilter) 동작을 "tokenless passthrough"로 변경한다. 현재는 미토큰 요청이 `isProtectedPath()` 판별 후 401을 반환하는데, 동적 라우팅 환경에서 게이트웨이가 prefix 재작성 전 경로를 보기 때문에 `/eeos/guest/…`가 `permitted-paths` 패턴과 불일치하여 오(誤) 401이 발생한다. 이를 해소하기 위해 인증 강제 역할을 다운스트림 `@PassportAuth`(econo-passport)에 완전히 이전하고, 인바운드 `X-User-Passport` 위조 방지를 위해 헤더 strip을 모든 요청에 대해 항상 선행 실행한다.

구현 대상: Java 21 / Spring Boot 3.2.2 / Spring Cloud Gateway(WebFlux), `services/apis/api-gateway` 모듈 단독 변경. DB 변경 없음.

---

## 본문

### 모듈 / 패키지 배치

| 모듈 / 패키지 | 신규 / 변경 | 사유 |
|---|---|---|
| `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/config/security/BearerToPassportFilter.java` | 변경 | 핵심 로직 변경(미토큰 passthrough + 인바운드 strip) |
| `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/config/GatewayRoutingConfig.java` | 변경 | `permittedPaths()` Javadoc 갱신(역할 축소 명시) |
| `services/apis/api-gateway/src/main/resources/application.yml` | 변경 | `gateway.permitted-paths` 블록 주석 갱신 |
| `services/apis/api-gateway/README.md` | 변경 | 요청 흐름·보안 섹션 갱신 |
| `services/apis/api-gateway/src/test/java/com/econo/auth/gateway/config/security/BearerToPassportFilterTest.java` | 변경 | 기존 케이스 수정 + 신규 케이스 추가 |
| `docs/adr/0017-gateway-tokenless-passthrough.md` | 신규 | ADR-0002 보완 결정 문서 |
| `docs/ARCHITECTURE.md` | 변경 | [흐름 B] 인증 흐름 및 모듈별 역할 표 갱신 |

---

### 구성 요소 설계

#### 모듈 / 패키지: `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/config/security/`

```
config/security/
├── BearerToPassportFilter.java    — (변경) GlobalFilter: 항상 strip → 미토큰 passthrough → 유효/무효 분기
├── GatewaySecurityConfig.java     — (유지) JwtVerifier 빈 등록, anyExchange().permitAll()
├── JwtVerifier.java               — (유지) ReactiveJwtDecoder 래퍼, RS256 검증
└── PassportBuilder.java           — (유지) JWT → Passport JSON → Base64
```

---

##### BearerToPassportFilter (변경)

- **타입**: GlobalFilter + Ordered (`config/security/` 패키지 — CONVENTION.md §1.1: GlobalFilter는 `config/security/` 전용)
- **책임**: 인바운드 `X-User-Passport` 무조건 제거 → Bearer/쿠키 토큰 추출 → 토큰 없음이면 strip된 exchange로 통과 → 유효 토큰이면 검증된 Passport 주입 → 무효 토큰이면 보호 경로 판별 후 401 또는 passthrough.
- **참조할 기존 코드**: `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/config/security/BearerToPassportFilter.java:53-86` (현재 `filter()` 전체)

**현재 → 목표 로직 비교**

| 분기 | 현재 동작 | 목표 동작 |
|---|---|---|
| 항상 | (없음) | `X-User-Passport` 인바운드 **무조건 제거** (mutatedBase 생성) |
| 미토큰 | `isProtectedPath` → true 이면 401, false 이면 pass | `isProtectedPath` 체크 **제거** → mutatedBase로 무조건 pass |
| 유효 토큰 | `exchange`에 `.header(PASSPORT_HEADER, encoded)` set | mutatedBase에 `.header(PASSPORT_HEADER, encoded)` set (인바운드 이미 제거된 base 위에 덮어씀) |
| 무효 토큰 | `isProtectedPath` → true 이면 401, false 이면 `exchange`로 pass | **기존과 동일**, 단 pass 시 mutatedBase 사용 |

**`filter()` 구현 상세 (pseudocode)**

```java
@Override
public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    // 1. 인바운드 X-User-Passport 항상 제거 (위조 방지) — 모든 분기 공통 base
    ServerWebExchange strippedExchange = exchange
        .mutate()
        .request(r -> r.headers(h -> h.remove(PASSPORT_HEADER)))
        .build();

    String path = exchange.getRequest().getPath().value();
    Optional<String> tokenOptional = extractBearerToken(exchange);

    // 2. 미토큰 → isProtectedPath 체크 없이 무조건 passthrough
    if (tokenOptional.isEmpty()) {
        log.debug("No bearer token, passing through, path={}", path);
        return chain.filter(strippedExchange);
    }

    // 3. 토큰 있음 → 검증
    String token = tokenOptional.get();
    return jwtVerifier
        .verify(token)
        .flatMap(jwt -> {
            // 4. 유효 → strip된 base에 검증 Passport 주입
            String encodedPassport = passportBuilder.buildAndSerialize(jwt);
            ServerWebExchange mutatedExchange = strippedExchange
                .mutate()
                .request(r -> r.header(PASSPORT_HEADER, encodedPassport))
                .build();
            return chain.filter(mutatedExchange);
        })
        .onErrorResume(e -> {
            // 5. 무효 → 보호 경로면 401, permitted면 strip된 base로 pass
            log.warn("JWT verification failed, path={}, error={}", path, e.getMessage());
            if (isProtectedPath(path)) {
                return rejectUnauthorized(strippedExchange);
            }
            return chain.filter(strippedExchange);
        });
}
```

**구현 포인트**

- `strippedExchange` 생성 시 `exchange.mutate().request(r -> r.headers(h -> h.remove(PASSPORT_HEADER))).build()` 사용. Spring Cloud Gateway `ServerWebExchangeUtils` 의존 없이 표준 WebFlux mutate API만 사용.
- `strippedExchange`를 미토큰 / 무효+permitted / 유효 세 분기 모두의 기본 base로 사용. 유효 분기는 `strippedExchange` 위에 추가 `.header()` set — `.header(name, value)`는 기존 값을 교체(put)하므로 단일 신뢰값 보장.
- `rejectUnauthorized(strippedExchange)` — 기존 `rejectUnauthorized(exchange)` 시그니처에서 파라미터만 교체. 응답에 상태코드를 설정하므로 exchange 본체가 필요하지 않으나, strip된 exchange를 사용해 일관성 유지. (실제로 `exchange.getResponse().setStatusCode(...)` 패턴은 `exchange` 원본에 설정하므로, response 조작은 `exchange`로 남겨도 무방 — 단, 명시적 일관성을 위해 `strippedExchange` 전달. `exchange.getResponse()`는 wrapping이 아닌 공유 참조이므로 동작에 차이 없음.)
- `extractBearerToken(exchange)` 호출 시 원본 `exchange` 사용 — Authorization 헤더 / at 쿠키 읽기는 인바운드 값 기준이므로 strip 전 exchange 필요.
- `isProtectedPath()` 메서드는 코드 변경 없이 무효 토큰 분기에서만 호출되도록 호출 지점만 축소(미토큰 분기에서 제거됨).

**Javadoc 갱신 내용**

클래스 수준 Javadoc에 다음을 추가:
- "인바운드 `X-User-Passport` 헤더는 위조 방지를 위해 경로·토큰 유무에 관계없이 항상 제거된다."
- "미토큰 요청은 경로 무관 passthrough한다. 인증 강제(401 거부)는 다운스트림 `@PassportAuth`(econo-passport)에 위임한다."
- "`permitted-paths`는 **무효 토큰 요청의 통과/거부 분기에서만** 사용한다. 미토큰 요청 분기에서는 참조하지 않는다."

- **주요 메서드/함수**:
  - `filter(ServerWebExchange, GatewayFilterChain) → Mono<Void>` — 위 로직 전체
  - `extractBearerToken(ServerWebExchange) → Optional<String>` — 변경 없음 (Authorization Bearer 헤더 → at 쿠키 순서 유지)
  - `isProtectedPath(String) → boolean` — 변경 없음 (PathPatternParser 기반 Ant 패턴 매칭), 호출 지점만 무효 토큰 분기로 축소
  - `rejectUnauthorized(ServerWebExchange) → Mono<Void>` — 변경 없음 (HTTP 401 + setComplete)
- **의존성**: `JwtVerifier`, `PassportBuilder`, `GatewayRoutingConfig` (변경 없음)
- **적용 컨벤션**:
  - `@Slf4j` + `@RequiredArgsConstructor` + `@Component` — CONVENTION.md §2.2 Lombok 사용 규칙 준수
  - `private static final` 상수는 UPPER_SNAKE_CASE — CONVENTION.md §1.4
  - Javadoc: 모든 public 클래스에 Javadoc 작성, private 메서드에 `/** 한 줄 설명 */` — CONVENTION.md §4.1/4.2
  - `log.warn()` 사용(무효 토큰 경고) — CONVENTION.md §3.2
  - Spotless + Google Java Format — CONVENTION.md §2.1 (빌드 후 `./gradlew format` 적용)
- **연관 todo**:
  - `[ ] filter() 메서드 최초 동작으로 인바운드 X-User-Passport 헤더 무조건 제거 추가`
  - `[ ] 미토큰 분기 변경: 토큰 없음 → isProtectedPath 체크 제거, 무조건 chain.filter(exchange) (passthrough)`
  - `[ ] 무효 토큰 onErrorResume 분기 유지`
  - `[ ] 유효 토큰 분기 유지`
  - `[ ] isProtectedPath() 메서드는 무효 토큰 분기에서만 사용하도록 호출 지점 축소`
  - `[ ] Javadoc 갱신`

---

##### GatewayRoutingConfig (Javadoc 변경)

- **타입**: `@Configuration` + `@ConfigurationProperties(prefix = "gateway")` (`config/` 패키지)
- **책임**: (기존 유지) `permittedPaths()` 제공 + 정적 보호 라우트 RouteLocator 빈 등록. 이번 변경은 Javadoc 갱신만.
- **Javadoc 갱신 내용** (`permittedPaths()` 메서드):
  - 기존: "BearerToPassportFilter에서 참조하는 인증 불필요 경로 목록."
  - 변경: "**무효 토큰** 요청의 통과/거부 분기에서만 사용. 미토큰 요청의 401 게이트 역할 **제거됨**. 미토큰은 경로 무관 passthrough."
- **참조할 기존 코드**: `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/config/GatewayRoutingConfig.java:46-48`
- **연관 todo**: `[ ] GatewayRoutingConfig.permittedPaths() 메서드 Javadoc 갱신`

---

##### application.yml (주석 변경)

- **타입**: 설정 파일
- **변경 위치**: `gateway.permitted-paths` 블록 앞에 인라인 주석 추가
- **갱신 내용**: `# 무효 토큰 분기 전용 — 미토큰은 경로 무관 passthrough (BearerToPassportFilter tokenless passthrough)`
- **참조할 기존 코드**: `services/apis/api-gateway/src/main/resources/application.yml:30-48`
- **연관 todo**: `[ ] application.yml의 gateway.permitted-paths 블록 주석 갱신`

---

##### api-gateway README.md (섹션 변경)

- **타입**: 운영 문서
- **변경 위치 및 내용**:

  1. **전체 요청 흐름 다이어그램** (`README.md:15-35`)
     - BearerToPassportFilter 내부 흐름에 `⓪ X-User-Passport 인바운드 제거(항상)` 스텝을 최상단에 추가.
     - `③ 토큰 없음 → 경로 무관 통과(다운스트림 위임)` 분기로 교체.

  2. **인증 불필요 경로 섹션** (`README.md:116-128`)
     - 역할 설명 수정: "무효 토큰 요청의 통과/거부 분기에서만 사용. 미토큰은 permitted-paths 참조 없이 passthrough."

  3. **X-User-Passport 헤더 섹션** (`README.md:89-111`)
     - 보안 노트에 추가: "> **위조 방지**: 인바운드 `X-User-Passport` 헤더는 항상 제거된다. Gateway만 이 헤더를 신뢰값으로 설정할 수 있다."

  4. **동작 변화 명시 박스 추가** (신규 섹션)
     ```
     > **[v2026-06-14 인증 경계 변경]**
     > 기존: 미토큰 + 보호경로 → 게이트웨이 401
     > 변경: 미토큰 + 보호경로 → passthrough (다운스트림 @PassportAuth가 401 처리)
     > 이유: 동적 라우팅 환경에서 게이트웨이가 prefix 재작성 전 경로를 보므로 permitted-paths 불일치 오 401 발생.
     ```

- **연관 todo**:
  - `[ ] "전체 요청 흐름" 다이어그램 수정`
  - `[ ] "인증 불필요 경로" 섹션 역할 설명 수정`
  - `[ ] "X-User-Passport 헤더" 섹션 보안 설명 추가`
  - `[ ] 동작 변화 명시 박스 추가`

---

#### 모듈 / 패키지: `services/apis/api-gateway/src/test/java/com/econo/auth/gateway/config/security/`

```
config/security/
└── BearerToPassportFilterTest.java    — (변경) 기존 케이스 3개 수정 + 신규 케이스 4개 추가
```

##### BearerToPassportFilterTest (변경)

- **타입**: 단위 테스트 (`@ExtendWith(MockitoExtension.class)`)
- **책임**: `BearerToPassportFilter` 분기 전체를 Mock 기반으로 검증.
- **참조할 기존 코드**: `services/apis/api-gateway/src/test/java/com/econo/auth/gateway/config/security/BearerToPassportFilterTest.java:1-351`
- **적용 컨벤션**: CONVENTION.md §5 — `@Nested` + `@DisplayName` 한글명, Given-When-Then 주석, AssertJ fluent assertion, `@ExtendWith(MockitoExtension.class)`, 메서드명 영문 camelCase.

**수정 케이스 목록**

| 위치 | 기존 테스트 이름 | 기존 기대 | 변경 후 기대 | 처리 방법 |
|---|---|---|---|---|
| `NoBearerTokenTest` | `noBearerOnProtectedPathReturns401` (line 194) | 미토큰+보호경로 → 401 | 미토큰+보호경로 → **통과(401 아님)** | `@DisplayName` + `assertThat` 수정 |
| `BearerPresentAndValidTest` | `authorizationHeaderWithoutBearerPrefixReturns401OnProtectedPath` (line 152) | Basic 헤더 → 401 | Basic 헤더 → **미토큰 취급 → 통과(401 아님)** | `assertThat` 수정 + `@DisplayName` 갱신 |
| `CookieBasedAuthRemovedTest` | `cookieOnlyWithoutBearerOnProtectedPathReturns401` (line 336) | auth_token 쿠키+보호경로 → 401 | auth_token 쿠키+보호경로 → **통과(401 아님)** | `assertThat` 수정 + `@DisplayName` 갱신 |

**신규 케이스 목록**

| `@Nested` 클래스 | 메서드명 | `@DisplayName` | 검증 내용 |
|---|---|---|---|
| `NoBearerTokenTest` | `noBearerOnProtectedPathPassesThrough` | "미토큰 + 보호경로 → passthrough (401 아님, Passport 미주입)" | `GET /api/v1/some/resource` Bearer 없음 → chain 호출됨, `X-User-Passport` 부재, statusCode != 401 |
| 신규 `InboundPassportStripTest` | `inboundForgeryHeaderIsStrippedOnNoToken` | "위조 X-User-Passport + 미토큰 → strip 후 통과 (다운스트림에 Passport 없음)" | 요청에 `X-User-Passport: forged` + Bearer 없음 → chain에 전달된 exchange의 `X-User-Passport` 헤더 부재, statusCode != 401 |
| 신규 `InboundPassportStripTest` | `inboundForgeryHeaderIsReplacedByVerifiedPassportOnValidToken` | "위조 X-User-Passport + 유효 토큰 → 검증 Passport로 교체" | 요청에 `X-User-Passport: forged` + `Authorization: Bearer <valid>` → chain에 전달된 exchange의 `X-User-Passport` == `passportBuilder.buildAndSerialize()` 반환값 (forged 아님) |
| `NoBearerTokenTest` (통합 or 유지) | `noBearerOnPermittedPathPassesThrough` | (기존 유지 — 이미 통과 기대) | 기존 케이스 그대로 유지(line 174) |

**신규 `@Nested` 클래스 설계**

```java
@Nested
@DisplayName("인바운드 X-User-Passport 위조 방지 테스트")
class InboundPassportStripTest {
    // 위 두 케이스
}
```

- `inboundForgeryHeaderIsStrippedOnNoToken`: 체인 람다 내부에서 `ex.getRequest().getHeaders().get(PASSPORT_HEADER)` → `isNull()` 검증.
- `inboundForgeryHeaderIsReplacedByVerifiedPassportOnValidToken`: 체인 람다 내부에서 `ex.getRequest().getHeaders().getFirst("X-User-Passport")` → `isEqualTo(encodedPassport)` (forged 값 아님). 이 케이스는 `given(jwtVerifier.verify(...)).willReturn(Mono.just(mockJwt))` + `given(passportBuilder.buildAndSerialize(mockJwt)).willReturn(encodedPassport)` mock 설정 필요.

- **연관 todo**:
  - `[ ] [기존 케이스 수정] NoBearerTokenTest.noBearerOnProtectedPathReturns401 삭제 또는 비활성화 후 대체`
  - `[ ] [신규] 미토큰 + 보호경로 → passthrough 확인 테스트`
  - `[ ] [신규] 인바운드 위조 X-User-Passport strip 확인 테스트`
  - `[ ] [신규] 유효 토큰 + 인바운드 위조 헤더 → strip 후 검증된 Passport로 덮어씀 확인 테스트`
  - `[ ] [기존 케이스 수정] authorizationHeaderWithoutBearerPrefixReturns401OnProtectedPath 재검토`
  - `[ ] [기존 케이스 유지] 무효 토큰 + 보호경로 → 401 (JwtVerificationFailTest 두 케이스)`
  - `[ ] [기존 케이스 유지] 무효 토큰 + permitted 경로 → passthrough`

---

#### 모듈 / 패키지: `docs/adr/`

##### 0017-gateway-tokenless-passthrough.md (신규)

- **타입**: ADR 문서
- **내용 구조**:
  - **상태**: Accepted
  - **결정일**: 2026-06-14
  - **관련**: ADR-0002 보완 (Supersede 아님 — "Gateway가 JWT 검증·Passport 주입" 원칙 유지), ADR-0016이 이 변경의 동기
  - **배경**: `BearerToPassportFilter`는 `@Order(-1)`로 `RouteToRequestUrlFilter`(order=10000)보다 먼저 실행되어 prefix 재작성 전 원시 경로(예: `/eeos/guest/…`)를 본다. 동적 라우팅(ADR-0016)에서 서비스가 `/eeos/**` prefix로 등록된 경우, `permitted-paths`의 `/api/guest/**` 패턴과 불일치 → 오 401 발생.
  - **결정**: 게이트웨이 인증 경계를 "enrich(Passport 주입) + 무효 토큰 거부"로 축소. 미토큰 요청은 경로 무관 passthrough. 인증 강제(미인증 거부)는 다운스트림 `@PassportAuth`에 위임.
  - **보안 보완**: 인바운드 `X-User-Passport` 헤더를 항상 strip → 게이트웨이만 이 헤더를 set할 수 있으므로 다운스트림 신뢰 모델 유지.
  - **결과**: `permitted-paths`의 역할이 "무효 토큰 요청의 통과/거부 분기 전용"으로 축소. 미토큰 401은 다운스트림 책임.
- **연관 todo**: `[ ] docs/adr/0017-gateway-tokenless-passthrough.md 작성`

---

#### 모듈 / 패키지: `docs/`

##### ARCHITECTURE.md (변경)

- **타입**: 아키텍처 문서
- **변경 위치 및 내용**:

  1. **[흐름 B] Bearer 토큰 → Passport 변환** 섹션 (`ARCHITECTURE.md:135-151`) — BearerToPassportFilter 흐름에 "인바운드 X-User-Passport 항상 제거" 스텝 최상단 추가 + "미토큰 → passthrough(다운스트림 @PassportAuth 위임)" 분기 추가.

  2. **모듈별 역할 표** (`ARCHITECTURE.md:76`) — `api-gateway` 역할 셀에 "인바운드 X-User-Passport strip(위조 방지) + 미토큰 passthrough(다운스트림 위임)" 추가.

  3. **핵심 설계 결정 §1 Gateway 책임 분리** (`ARCHITECTURE.md:337-340`) — "미토큰 요청은 게이트웨이를 통과하며, 인증 강제는 다운스트림 `@PassportAuth`가 담당한다. 위조 방지를 위해 인바운드 `X-User-Passport` 헤더는 항상 제거된다." 추가.

- **연관 todo**: (직접 명시 todo 없으나, 설계 확정 요건 "docs/ARCHITECTURE.md [흐름 B] 갱신" 명시됨)

---

### 호출 흐름

#### 정상 경로 1 — 미토큰 요청 (신규 동작)

```
Client(no token)
  → BearerToPassportFilter.filter()
    → exchange.mutate().request(r -> r.headers(h -> h.remove("X-User-Passport"))).build()
       → strippedExchange 생성 (인바운드 Passport 제거)
    → extractBearerToken(exchange) → Optional.empty()
    → chain.filter(strippedExchange)
  → Spring Cloud Gateway 라우팅
  → Downstream Service (X-User-Passport 없음)
    → @PassportAuth(required=true) → 401 거부 OR
    → @PassportAuth(required=false) → 정상 처리
```

#### 정상 경로 2 — 유효 Bearer 토큰

```
Client(Bearer <valid_jwt>)
  → BearerToPassportFilter.filter()
    → X-User-Passport 인바운드 제거 → strippedExchange
    → extractBearerToken(exchange) → Optional.of(token)
    → jwtVerifier.verify(token) → Mono<Jwt>
    → passportBuilder.buildAndSerialize(jwt) → encodedPassport
    → strippedExchange.mutate().request(r -> r.header("X-User-Passport", encodedPassport)).build()
    → chain.filter(mutatedExchange)
  → Downstream Service (X-User-Passport = 검증된 Passport Base64)
    → PassportArgumentResolver.resolveArgument() → Passport 객체
```

#### 정상 경로 3 — 위조 X-User-Passport + 유효 Bearer 토큰

```
Client(X-User-Passport: forged, Bearer <valid>)
  → BearerToPassportFilter.filter()
    → X-User-Passport: forged 제거 → strippedExchange (헤더 없는 상태)
    → jwtVerifier.verify(token) → Mono<Jwt>
    → strippedExchange에 검증된 encodedPassport set
    → chain.filter(mutatedExchange)
  → Downstream (X-User-Passport = 검증된 값, forged 아님)
```

#### 예외 경로 1 — 무효 토큰 + 보호 경로

```
Client(Bearer <invalid_jwt>)
  → BearerToPassportFilter.filter()
    → strippedExchange 생성
    → extractBearerToken() → Optional.of(invalidToken)
    → jwtVerifier.verify(invalidToken) → Mono.error(BadJwtException | JwtValidationException)
    → onErrorResume:
        log.warn(...)
        isProtectedPath("/api/v1/some/resource") → true
        rejectUnauthorized(strippedExchange) → HTTP 401
```

#### 예외 경로 2 — 무효 토큰 + permitted 경로

```
Client(Bearer <invalid_jwt>, path=/api/v1/auth/login)
  → BearerToPassportFilter.filter()
    → strippedExchange 생성
    → jwtVerifier.verify(invalidToken) → Mono.error(BadJwtException)
    → onErrorResume:
        isProtectedPath("/api/v1/auth/login") → false (permitted)
        chain.filter(strippedExchange)   ← passthrough
  → Downstream (X-User-Passport 없음)
```

#### 예외 경로 3 — 위조 X-User-Passport + 미토큰 (위조 방지 확인)

```
Client(X-User-Passport: forged, no Bearer)
  → BearerToPassportFilter.filter()
    → X-User-Passport: forged 제거 → strippedExchange
    → extractBearerToken() → Optional.empty()
    → chain.filter(strippedExchange)   ← X-User-Passport 없는 exchange 통과
  → Downstream (X-User-Passport 없음 — 위조 헤더 무력화)
```

---

### 컨벤션 준수 항목

- **네이밍**: 클래스명 `BearerToPassportFilter` 유지(PascalCase + 역할 접미사). 지역 변수 `strippedExchange` camelCase. 상수 `PASSPORT_HEADER` UPPER_SNAKE_CASE — CONVENTION.md §1.2/1.3/1.4.
- **패키지**: GlobalFilter는 `config/security/` 전용 — CONVENTION.md §1.1 "`presentation/filter` 패키지는 사용하지 않는다."
- **의존성 주입**: `@RequiredArgsConstructor` (final 필드 기반 생성자) — CONVENTION.md §2.2.
- **로깅**: `log.warn()` 무효 토큰 경고, `log.debug()` 미토큰 통과(변경 분기) — CONVENTION.md §3.2 에러 전파 규칙 준수.
- **예외 처리**: `onErrorResume`에서 `log.warn()` 후 분기 결정 — 기존 패턴 유지. `rejectUnauthorized()`는 HTTP 401 직접 설정(PassportException 미사용, 게이트웨이 필터 계층이라 예외 팩토리 패턴 적용 대상 아님).
- **불변성**: `strippedExchange`, `mutatedExchange`는 각각 새 인스턴스 — WebFlux ServerWebExchange mutate 패턴.
- **Javadoc**: `public` 클래스·메서드에 Javadoc 갱신, private 메서드에 `/** 한 줄 설명 */` — CONVENTION.md §4.
- **테스트 패턴**: `@Nested` + `@DisplayName` 한글, Given-When-Then 주석, AssertJ fluent assertion, `@ExtendWith(MockitoExtension.class)` — CONVENTION.md §5.
- **포맷팅**: 변경 후 `./gradlew format` 적용 (Spotless + Google Java Format 1.17.0) — CONVENTION.md §2.1.
- **빌드 검증**: `./gradlew :services:apis:api-gateway:build` (컴파일 + 테스트 전체) — todo 빌드 검증 항목.

---

## 체크리스트
- [x] todo의 모든 구현 작업이 구성 요소로 매핑됨
- [x] 모든 구성 요소가 적절한 모듈/계층에 배치됨 (`config/security/` GlobalFilter 컨벤션 준수)
- [x] 모든 구성 요소가 적용 컨벤션을 명시함
- [x] 호출 흐름에 빠진 분기가 없음 (미토큰 passthrough / 유효 / 무효+보호 / 무효+permitted / 위조헤더 무력화)

---

## 참고
- `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/config/security/BearerToPassportFilter.java` — 변경 대상 (현재 전체 로직 기준)
- `services/apis/api-gateway/src/test/java/com/econo/auth/gateway/config/security/BearerToPassportFilterTest.java` — 갱신 대상 단위 테스트
- `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/config/GatewayRoutingConfig.java` — Javadoc 갱신 대상
- `services/apis/api-gateway/src/main/resources/application.yml` — 주석 갱신 대상
- `services/apis/api-gateway/README.md` — 섹션 갱신 대상
- `docs/adr/0002-gateway-as-auth-boundary.md` — ADR-0017이 보완하는 기존 ADR
- `docs/adr/0016-dynamic-gateway-routing-reintroduction.md` — 이 변경의 동기가 된 동적 라우팅 ADR
- `docs/ARCHITECTURE.md` — [흐름 B] 및 모듈별 역할 갱신 대상
- `docs/CONVENTION.md` — 컨벤션 기준
