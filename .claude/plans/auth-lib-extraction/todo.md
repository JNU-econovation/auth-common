# auth-lib-extraction - todo

## 메타
- **작업명**: auth-lib-extraction
- **문서 타입**: todo
- **작성일**: 2026-06-13
- **관련 문서** (같은 디렉터리):
  - api-design-plan.md
  - implementation-plan.md
  - db-design-plan.md

## 개요
auth-api의 application 계층(`LoginTokenService`, `LoginRedirectResolver`, 두 UseCase 인터페이스)을 새 lib 모듈 `services/libs/login`(패키지 `com.econo.auth.login`)로 추출한다. 토큰 서명/검증을 도메인 포트 `TokenEncoder`·`TokenDecoder`로 추상화하여 lib이 `spring-security-oauth2` 의존을 0으로 유지하고, Nimbus 기반 구현체(`NimbusTokenManager`)는 두 포트를 모두 구현하는 단일 클래스로 auth-api의 `config/security/`에 배치된다. 엔드포인트·요청/응답·DB 스키마는 변경 없는 순수 리팩토링이며, 기존 통합 테스트(`AuthApiIntegrationTest`) green을 회귀 안전망으로 삼는다.

## 본문

### API 작업
- 변경 없음(기존 엔드포인트·요청/응답·HTTP 상태 코드 전부 유지). 이번 작업은 순수 내부 리팩토링이다.

### 구현 작업

#### [lib 모듈 신설 — services/libs/login]

- [ ] `services/libs/login/` 디렉터리 구조 생성: `src/main/java/com/econo/auth/login/` 하위에 `config/`, `application/usecase/`, `application/service/`, `application/repository/`, `application/domain/`, `exception/` 패키지 신설
- [ ] `application/domain/TokenModel` record 작성 — 필드: `issuer(String)`, `subject(String)`, `issuedAt(Instant)`, `expiresAt(Instant)`, `claims(Map<String,Object>)`. Spring/JWT 타입 의존 없음
- [ ] `application/domain/DecodedToken` record 작성 — 필드: `subject(String)`, `claims(Map<String,Object>)`. Spring/JWT 타입 의존 없음
- [ ] `application/repository/TokenEncoder` 인터페이스 작성 — `String encode(TokenModel model)`. 도메인 타입만 사용, Spring 타입 의존 없음
- [ ] `application/repository/TokenDecoder` 인터페이스 작성 — `DecodedToken decode(String token) throws InvalidTokenException`. 도메인 타입만 사용, Spring 타입 의존 없음
- [ ] `exception/InvalidTokenException` 작성 — `decode()` 실패 시 throw, Spring 타입 의존 없음
- [ ] `exception/WrongTokenTypeException` 작성 — `token_type` 불일치 시 throw
- [ ] `application/usecase/LoginTokenUseCase` 인터페이스 작성 — 메서드: `TokenPair issue(Member)`, `TokenPair reissue(Long memberId)`, `Long verifyRefreshTokenAndGetMemberId(String rawRt)`. `TokenPair` record(`accessToken`, `accessExpiredAt`, `refreshToken`) 포함. 기존 `extractMemberIdFromRt(Jwt)` 제거(Jwt 타입 제거). Spring Security OAuth2 import 없음
- [ ] `application/usecase/LoginRedirectUseCase` 인터페이스 작성 — `String resolve(String clientId, String defaultUrl)`. 기존 auth-api 버전에서 패키지만 이동
- [ ] `application/service/LoginTokenService` 작성 (`@Service`) — `JwtEncoder`/`JwtDecoder` 필드 제거, `TokenEncoder`·`TokenDecoder` 두 포트 주입. AT/RT 클레임을 `TokenModel`로 조립해 `encoder.encode()` 호출. `verifyRefreshTokenAndGetMemberId`는 `decoder.decode()` 후 `claims.get("token_type")`이 `"refresh"`가 아니면 `WrongTokenTypeException`, 디코딩 실패(`InvalidTokenException` 전파). `@Value` issuer/expiry 유지
- [ ] `application/service/LoginRedirectResolver` 작성 (`@Service`) — 기존 auth-api 로직 그대로 복사, 패키지 `com.econo.auth.login.application.service`로 변경, `@Service` 추가(자동 등록 전환)
- [ ] `config/LoginAutoConfiguration` 작성 — `@AutoConfiguration` + `@ComponentScan("com.econo.auth.login")`. `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`에 `com.econo.auth.login.config.LoginAutoConfiguration` 등록

#### [auth-api 측 변경]

- [ ] `config/security/NimbusTokenManager` 신설 (`implements TokenEncoder, TokenDecoder`, `@Component`, `JwtEncoder` + `JwtDecoder` 주입) — `encode(TokenModel)`: `TokenModel` → `JwtClaimsSet` + `JwsHeader.RS256` 조립 후 `jwtEncoder.encode()` 호출, token 문자열 반환. `decode(String token)`: `jwtDecoder.decode(token)` 후 `Jwt` → `DecodedToken` 변환, `JwtException` 발생 시 `InvalidTokenException`으로 래핑. Spring이 `LoginTokenService`의 `TokenEncoder`·`TokenDecoder` 두 파라미터를 이 단일 빈으로 해소
- [ ] `ReissueController` 수정 — `JwtDecoder`·`Jwt` 필드 및 관련 import 제거. `rawRt` null/blank 체크는 컨트롤러에 유지(기존 `REFRESH_TOKEN_MISSING` 동작 보존). `loginTokenUseCase.verifyRefreshTokenAndGetMemberId(rawRt)` 호출로 변경. `InvalidTokenException`과 `WrongTokenTypeException` catch → 기존 401 `REFRESH_TOKEN_INVALID` 응답 반환
- [ ] `ApplicationServiceConfig` 수정 — `loginRedirectResolver @Bean` 메서드 제거(lib `@Service` 자동 등록으로 전환). `signupService @Bean` 유지. 구 `com.econo.auth.api.application.service.LoginRedirectResolver` import 제거
- [ ] `SecurityConfig` import 갱신 — `LoginTokenUseCase`, `LoginRedirectUseCase`를 `com.econo.auth.login.application.usecase.*`로 변경
- [ ] `JsonLoginAuthenticationFilter` import 갱신 — `LoginTokenUseCase`, `LoginRedirectUseCase`, `TokenPair`를 `com.econo.auth.login.application.usecase.*`로 변경
- [ ] auth-api에서 구 application 패키지 4파일 제거 — `com.econo.auth.api.application.usecase.LoginTokenUseCase`, `com.econo.auth.api.application.usecase.LoginRedirectUseCase`, `com.econo.auth.api.application.service.LoginTokenService`, `com.econo.auth.api.application.service.LoginRedirectResolver`

### DB 작업
- 변경 없음(신규 테이블·컬럼·인덱스·마이그레이션 없음). 순수 리팩토링.

### 기타 작업

#### [빌드 설정]

- [ ] `services/libs/login/build.gradle.kts` 신설 — 의존: `implementation(project(":services:libs:member"))`, `implementation(project(":services:libs:service-client"))`, `implementation("org.springframework.boot:spring-boot-starter")` (spring-context 포함), `compileOnly("org.projectlombok:lombok")`, `annotationProcessor("org.projectlombok:lombok")`. `spring-security-oauth2-jose`, `spring-security-oauth2-jwt` 의존 **추가 금지**
- [ ] `settings.gradle.kts` 수정 — `include("services:libs:login")` 추가 (libs 섹션)
- [ ] `services/apis/auth-api/build.gradle.kts` 수정 — `implementation(project(":services:libs:login"))` 추가

#### [테스트]

- [ ] `LoginTokenServiceTest` 재작성 및 이동 — `services/libs/login/src/test/java/com/econo/auth/login/application/service/LoginTokenServiceTest.java`로 이동. `JwtEncoder`/`JwtDecoder` mock 폐기, `@Mock TokenEncoder encoder`·`@Mock TokenDecoder decoder` 두 mock 방식으로 재작성. `encoder.encode()` 호출 시 `TokenModel` 검증(AT/RT 클레임 포함 여부, `token_type` 값), `verifyRefreshTokenAndGetMemberId` — `token_type=access` 시 `WrongTokenTypeException`, `decoder.decode()` 실패 시 `InvalidTokenException` 전파 검증
- [ ] `LoginRedirectResolverTest` 이동 — `services/libs/login/src/test/java/com/econo/auth/login/application/service/LoginRedirectResolverTest.java`로 이동. 기존 6개 시나리오 그대로 유지, 패키지 import만 갱신
- [ ] `NimbusTokenManager` 단위 테스트 신설 (`services/apis/auth-api/src/test/.../config/security/NimbusTokenManagerTest`) — `@Nested`로 encode/decode 그룹 분리. encode: `JwtEncoder` 호출 후 token 문자열 반환 검증, `TokenModel`의 issuer/subject/issuedAt/expiresAt/claims가 `JwtClaimsSet`에 올바르게 매핑됨 검증. decode: 성공 시 `DecodedToken` 반환 검증, `JwtException` 발생 시 `InvalidTokenException`으로 래핑 검증
- [ ] 회귀 확인: `AuthApiIntegrationTest` (로그인·재발급·AT-as-RT→401·로그아웃) 전 시나리오 green 유지 확인

#### [검증]

- [ ] `./gradlew clean build` 전체 green 확인 (컴파일 + 테스트 + spotless)
- [ ] auth lib 의존 검사: `./gradlew :services:libs:login:dependencies | grep spring-security-oauth2` 결과 없음 확인
- [ ] 순환 의존 없음 확인: auth → member (단방향), auth → service-client (단방향)
- [ ] iCloud 환경 안전 확인: clean 빌드 후 `*.java` 파일 중복 생성본(`*\ 2.java`) 없음 확인 및 있을 경우 `rm` 제거

#### [문서]

- [ ] `docs/ARCHITECTURE.md` 수정 — "모듈 구조" 섹션에 `services/libs/login` 추가, "모듈 의존성" 다이어그램에 `auth → member`, `auth → service-client`, `auth-api → auth` 관계 추가, "모듈별 역할" 테이블에 auth lib 행 추가
- [ ] `docs/ARCHITECTURE.md` 수정 — "보류 사항(auth-api application 계층 lib 추출 / TokenEncoder·TokenDecoder 포트)" 항목을 "완료" 처리로 갱신 또는 제거
- [ ] `docs/adr/0014-3-layer-dip-architecture.md` 수정 — 보류 사항 두 항목("auth-api application 계층 lib 추출", "토큰 발급 추상화") 완료 처리
- [ ] `services/libs/login/README.md` 작성 — README-GUIDE.md 필수 섹션 준수: Quick Reference(패키지, Gradle 경로), 비즈니스 규칙(TokenEncoder/TokenDecoder 포트 설계 의도, lib에 oauth2 의존 금지 이유), 코드 진입점, 에러 코드(`InvalidTokenException`, `WrongTokenTypeException`), 관련 모듈
- [ ] 토큰 포트 추상화 결정 ADR 작성 검토 (선택) — `docs/adr/0015-token-encoder-decoder-port-abstraction.md`. lib 프레임워크 격리 결정 근거 및 트레이드오프 기술

## 체크리스트
- [ ] todo가 빠짐없이 추출됨
- [ ] 각 항목이 PR 단위로 충분히 잘게 쪼개짐
- [ ] 카테고리 매핑이 명확함
- [ ] 모호함 없이 후속 designer가 바로 작업 가능

## 참고
- `docs/ARCHITECTURE.md` — 모듈 구조, 계층 모델, 의존성 규칙
- `docs/CONVENTION.md` — 패키지 컨벤션, 클래스 네이밍 규칙
- `docs/adr/0014-3-layer-dip-architecture.md` — 3계층 DIP 채택 근거 및 보류 사항
- `docs/README-GUIDE.md` — lib README 작성 필수 섹션 규칙
- `services/apis/auth-api/src/main/java/com/econo/auth/api/application/service/LoginTokenService.java` — 추출 대상 원본
- `services/apis/auth-api/src/main/java/com/econo/auth/api/application/service/LoginRedirectResolver.java` — 추출 대상 원본
- `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/controller/ReissueController.java` — 변경 대상
- `services/apis/auth-api/src/main/java/com/econo/auth/api/config/ApplicationServiceConfig.java` — 변경 대상
- `services/apis/auth-api/src/main/java/com/econo/auth/api/config/RsaKeyConfig.java` — NimbusTokenManager가 jwtEncoder/jwtDecoder 빈을 소비
- `services/libs/member/src/main/java/com/econo/auth/member/config/MemberAutoConfiguration.java` — LoginAutoConfiguration 설계 참고
