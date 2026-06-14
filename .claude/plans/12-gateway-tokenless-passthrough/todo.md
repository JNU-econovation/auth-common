# gateway-tokenless-passthrough - todo

## 메타
- **작업명**: gateway-tokenless-passthrough
- **문서 타입**: todo
- **작성일**: 2026-06-14
- **관련 문서** (같은 디렉터리):
  - (이 작업은 단일 todo.md로 충분; 별도 design plan 파일 없음)

## 개요

`api-gateway`의 `BearerToPassportFilter`(GlobalFilter) 동작을 "tokenless passthrough"로 변경한다.
기존에는 미토큰 요청이 보호 경로에 도달하면 게이트웨이가 401을 반환했으나, 동적 라우팅(`/eeos/**` → 다운스트림 prefix) 환경에서 게이트웨이 `permitted-paths`(인바운드 경로 기준)와 실제 경로가 맞지 않아 게스트/퍼블릭 경로가 오(誤) 401 되는 문제가 발생한다.
이를 해소하기 위해 게이트웨이의 역할을 "JWT가 있으면 검증·주입, 없으면 그대로 통과"로 좁히고, 인증 강제(401 거부)를 다운스트림 `@PassportAuth`(econo-passport)로 완전히 이전한다.
인바운드 `X-User-Passport` 위조 방지를 위해 헤더 strip은 항상(전 경로·전 경우) 먼저 실행한다.

---

## 본문

### API 작업
- 해당 없음 (신규 엔드포인트 추가·기존 엔드포인트 시그니처 변경 없음)

### 구현 작업

#### BearerToPassportFilter 로직 변경
- [ ] `filter()` 메서드 최초 동작으로 **인바운드 `X-User-Passport` 헤더 무조건 제거** 추가
  - `exchange.mutate().request(r -> r.headers(h -> h.remove("X-User-Passport"))).build()`
  - 위조 방지: 토큰 유무·경로 분기 이전에 항상 실행
- [ ] **미토큰 분기 변경**: 토큰 없음 → `isProtectedPath` 체크 제거, 무조건 `chain.filter(exchange)` (passthrough)
  - 기존: `if (tokenOptional.isEmpty()) { if (isProtectedPath) → 401; else → pass }`
  - 변경: `if (tokenOptional.isEmpty()) { → pass (X-User-Passport 미주입) }`
- [ ] **무효 토큰 onErrorResume 분기 유지**: 무효(만료·서명 오류) 토큰 + 보호 경로 → 401, 무효 + permitted → passthrough (기존 동작 그대로)
- [ ] **유효 토큰 분기 유지**: `JwtVerifier.verify` → `PassportBuilder.buildAndSerialize` → X-User-Passport 주입(strip 후 검증된 값 set) → `chain.filter(mutatedExchange)` (기존과 동일, strip 선행으로 인바운드 덮어쓰기 보장)
- [ ] `isProtectedPath()` 메서드는 무효 토큰 분기에서만 사용하도록 호출 지점 축소(미토큰 분기에서 호출 제거)
- [ ] Javadoc 갱신: 클래스 주석에 "미토큰 요청은 경로 무관 passthrough(다운스트림 @PassportAuth에 위임)" 명시, `permitted-paths`의 용도 변경("무효 토큰 거부 분기에서만 사용") 반영

#### GatewayRoutingConfig / application.yml 주석 갱신
- [ ] `GatewayRoutingConfig.permittedPaths()` 메서드 Javadoc 갱신: "무효 토큰 요청의 통과/거부 분기에서만 사용. 미토큰 401 게이트 역할 제거."
- [ ] `application.yml`의 `gateway.permitted-paths` 블록 주석 갱신: 용도 변경 내용 한 줄 명시 ("무효 토큰 분기 전용; 미토큰은 경로 무관 passthrough")

#### api-gateway README.md 갱신
- [ ] "전체 요청 흐름" 다이어그램에서 미토큰 분기 설명 수정: "③ 토큰 없음 → 경로 무관 통과(다운스트림 위임)" 추가
- [ ] "인증 불필요 경로" 섹션의 역할 설명 수정: permitted-paths가 무효 토큰 분기에서만 사용됨을 명기
- [ ] "X-User-Passport 헤더" 섹션에 "인바운드 X-User-Passport는 항상 제거(위조 방지)" 보안 설명 추가
- [ ] 동작 변화 명시 박스 추가: "기존: 미토큰+보호경로 → 게이트웨이 401 / 변경: 미토큰+보호경로 → passthrough(다운스트림 @PassportAuth가 401 처리)"

### DB 작업
- 해당 없음 (스키마·마이그레이션 변경 없음)

### 기타 작업

#### 단위 테스트 — BearerToPassportFilterTest 갱신/추가
- [ ] **[기존 케이스 수정]** `NoBearerTokenTest` — `noBearerOnProtectedPathReturns401` 삭제 또는 비활성화 후 대체 케이스 작성
  - 변경 전 기대: 미토큰 + 보호경로 → 401
  - 변경 후 기대: 미토큰 + 보호경로 → **통과(401 아님)**
- [ ] **[신규]** 미토큰 + 보호경로 → passthrough 확인 테스트
  - 요청: `GET /api/v1/some/resource` (Bearer 없음)
  - 기대: HTTP 상태 401 아님, `X-User-Passport` 헤더 부재
- [ ] **[신규]** 미토큰 + permitted 경로 → passthrough 확인 테스트 (기존 `noBearerOnPermittedPathPassesThrough`와 통합 또는 유지)
- [ ] **[기존 케이스 유지]** 무효 토큰 + 보호경로 → 401 (`JwtVerificationFailTest.signatureErrorOnProtectedPathReturns401`, `expiredJwtOnProtectedPathReturns401`)
- [ ] **[기존 케이스 유지]** 무효 토큰 + permitted 경로 → passthrough (`jwtFailureOnPermittedPathPassesThrough`)
- [ ] **[신규]** 인바운드 위조 `X-User-Passport` strip 확인 테스트
  - 요청: `GET /api/v1/some/resource` + 헤더 `X-User-Passport: forged-value` (Bearer 없음)
  - 기대: 다운스트림 전달 시 `X-User-Passport` 헤더 부재(strip됨), 401 아님
- [ ] **[신규]** 유효 토큰 + 인바운드 위조 헤더 → strip 후 검증된 Passport로 덮어씀 확인 테스트
  - 요청: `GET /api/v1/some/resource` + `Authorization: Bearer <valid>` + `X-User-Passport: forged`
  - 기대: 체인에 전달된 exchange의 `X-User-Passport`가 `passportBuilder.buildAndSerialize()` 반환값과 일치 (forged 아님)
- [ ] **[기존 케이스 수정]** `BearerPresentAndValidTest.authorizationHeaderWithoutBearerPrefixReturns401OnProtectedPath` 재검토
  - Basic 인증 헤더(Bearer 아님) → 토큰 추출 실패 → 미토큰 취급 → passthrough로 바뀌므로 기대값 수정 필요

#### 통합 테스트 (ApiGatewayApplicationContextTest)
- [ ] 기존 `contextLoads()` 테스트가 변경된 필터 포함 컨텍스트를 정상 로드하는지 그린 확인 (별도 코드 변경 없이 빌드 통과로 검증)
- [ ] (선택, 메모리상 게이트웨이 통합 테스트 갭 있음) `@SpringBootTest` + `WebTestClient`로 미토큰 요청이 실제로 라우팅 단계까지 도달하는지 확인하는 smoke test 추가 검토 — 업스트림 다운스트림 없이 4xx 아닌 응답(예: 502)으로 passthrough 증명

#### ADR 신규 작성
- [ ] `docs/adr/0017-gateway-tokenless-passthrough.md` 작성
  - **상태**: Accepted
  - **Supersedes / 관련**: ADR-0002를 갱신하는 결정임을 명시 (ADR-0002는 "Gateway가 JWT 검증·Passport 주입"이라는 핵심은 유지, "미토큰 401 거부" 역할만 철회)
  - **배경**: 동적 라우팅(service_route prefix 재작성) 환경에서 `BearerToPassportFilter`가 `@Order(-1)`로 prefix 재작성 전 경로를 보므로 `/eeos/guest/...`가 `/api/guest/**` permitted 패턴과 불일치 → 오 401 발생
  - **결정**: 게이트웨이 인증 경계를 "enrich(Passport 주입) + 무효 토큰 거부"로 좁힘. 미토큰 요청은 경로 무관 passthrough. 인증 강제는 다운스트림 `@PassportAuth`에 위임.
  - **보안 보완**: 인바운드 X-User-Passport 헤더 항상 strip → 게이트웨이만 set 가능하므로 다운스트림 신뢰 모델 유지
  - **결과**: permitted-paths의 역할 축소(무효 토큰 분기 전용), 미토큰 요청의 401은 다운스트림 책임

#### 빌드 검증
- [ ] `./gradlew :services:apis:api-gateway:build` 그린 확인 (컴파일 + 전체 테스트 통과)

---

## 체크리스트
- [ ] todo가 빠짐없이 추출됨
- [ ] 각 항목이 PR 단위로 충분히 잘게 쪼개짐
- [ ] 카테고리 매핑이 명확함
- [ ] 모호함 없이 후속 implementer가 바로 작업 가능

## 참고
- `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/config/security/BearerToPassportFilter.java` — 변경 대상 필터
- `services/apis/api-gateway/src/test/java/com/econo/auth/gateway/config/security/BearerToPassportFilterTest.java` — 갱신 대상 단위 테스트
- `services/apis/api-gateway/src/main/resources/application.yml` — `gateway.permitted-paths` 주석 갱신
- `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/config/GatewayRoutingConfig.java` — `permittedPaths()` Javadoc 갱신
- `services/apis/api-gateway/README.md` — 요청 흐름 다이어그램·섹션 갱신
- `docs/adr/0002-gateway-as-auth-boundary.md` — 신규 ADR-0017이 참조·보완하는 기존 ADR
- `docs/adr/0016-dynamic-gateway-routing-reintroduction.md` — 동적 라우팅 ADR (이 작업의 동기)
- `services/apis/api-gateway/src/test/java/com/econo/auth/gateway/ApiGatewayApplicationContextTest.java` — 컨텍스트 로드 통합 테스트
