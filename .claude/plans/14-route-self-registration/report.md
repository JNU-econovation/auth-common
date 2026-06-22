# route-self-registration - report

## 메타
- **작업명**: route-self-registration
- **작성일**: 2026-06-22
- **브랜치**: feat/route-self-registration
- **plan 문서**:
  - todo.md
  - api-design-plan.md
  - implementation-plan.md
  - db-design-plan.md

## 배경
회원 셀프서비스로 가능한 것은 클라이언트 등록뿐이고, 게이트웨이 라우트는 관리자만 등록할 수 있어 "로그인은 되지만 라우팅은 안 되는" 병목이 있었다. 이를 해소하되, **별도 라우트 CRUD 5개 엔드포인트(초기 설계)를 폐기**하고 **기존 회원 셀프 클라이언트 등록 `POST /api/v1/clients`에 라우트 등록을 흡수**하는 방향으로 전환했다. 회원이 자기 서비스를 등록할 때 클라이언트(OAuth) + 라우트(게이트웨이)를 한 번에 선언한다.

## 설계 전환 경위 (초기 설계 → 최종 설계)
- **초기 설계(폐기)**: `/api/v1/routes` 회원 셀프 CRUD 5개 + owner_id + 네임스페이스 강제 + 쿼터 + PUT 불변. test→implement→Green까지 갔으나, 기획 방향 변경으로 폐기.
- **최종 설계(채택)**: 라우트 등록을 클라이언트 셀프 등록에 흡수. 1 클라이언트 = 최대 1 라우트. 라우트 조회/수정/삭제 엔드포인트 없음(추후 클라이언트 목록/상세 조회 API가 라우팅 정보 노출 예정).
- 초기 설계 산출물 중 **재사용**: `RouteValidator`, `RouteNamespaceExtractor`, `RouteNamespaceInvalid/TakenException`, `ServiceRoute.ownerId`, `findNamespaceOwner`, V11/V12 마이그레이션, `ManageRouteService`의 `RouteValidator` 추출.
- 초기 설계 산출물 중 **제거**: `RouteController`/`RouteApiDocs`/`SelfCreate·UpdateRouteRequest`/`SelfManageRouteUseCase·Service`, 예외 3종(`RouteAccessDenied`·`RouteNamespaceImmutable`·`RouteQuotaExceeded`), `/api/v1/routes/**` 보호경로(GatewayRoutingConfig·ProtectedPathPolicyImpl·SecurityConfig), 핸들러 3종, 테스트 2종.

## 진행 결과

### 0. 클린업 (설계 전환)
- 삭제 11파일 + 설정 3파일 원복(`/api/v1/routes/**` 제거) + `GlobalExceptionHandler` 핸들러 3개·`ApplicationServiceConfig` `selfManageRouteService` 빈 제거.
- baseline Green 확인(재사용 자산 유지).

### 1. test
- 확장 2파일: `ClientControllerTest`(라우트 흡수 8케이스 + 기존 시그니처 갱신), `RegisterOAuthClientServiceTest`(라우트 분기 9케이스 + 기존 갱신).
- Red 확인: 신규 필드/시그니처 미존재로 양 모듈 테스트 컴파일 에러.

### 2. implementation
- 변경 11파일.
  - service-client: `RegisterOAuthClientUseCase`(Command 5인자·Result 6인자), `RegisterOAuthClientService`(라우트 분기 + afterCommit refresh, `@Service`→수동 `@Bean`), 리포지토리 3종(미사용 `findAllByOwnerId`·`countByOwnerId` 제거, `findNamespaceOwner`/`findOwnerIdsByNamespace` 유지).
  - auth-api: `SelfRegisterClientRequest`(라우트 필드 + `@AssertTrue isRouteFields()` 쌍 검증), `SelfRegisterClientResponse`(라우트 4필드), `ClientController`, `ClientApiDocs`, `ApplicationServiceConfig`(`registerOAuthClientService` 수동 @Bean).
- Green: service-client·auth-api 전체 테스트 + api-gateway 컴파일 + 전체 spotlessCheck 통과(Docker 가동, @SpringBootTest 컨텍스트 포함).

### 3. code-review (1회)
- 반영 권장 8 / 참고 5. 권장 8건 전부 반영.
  - major: #2 `findOwnerIdByNamespace` 복수결과 안전화(List + null 필터 → 어드민(owner=null) 라우트 공존 시 500 방지), #3·#4 삭제 클래스 `{@link SelfManageRouteService}` Javadoc 정리, #5 V12 주석 정정.
  - minor: #1 `routeId` 단언 `nullValue()` 명확화, #6 `@AssertTrue`→`routeFields` 정합, #7 테스트 Javadoc V11/V12, #8 stub 명시.
- 재검증: `--rerun-tasks` 전체 재실행 Green.

### 4. docs
- 갱신 5문서: `docs/CLIENT_REGISTRATION.md`(핵심), `docs/DYNAMIC_ROUTING.md`, `docs/ARCHITECTURE.md`, `services/libs/service-client/README.md`, `services/apis/auth-api/README.md`.

### 5. doc-review (1회)
- 반영 권장 3 + 참고 2, 전부 반영.
  - #1(critical) service-client README `clientSecret` 저장 위치 정반대 기술 정정(실제: `service_client.client_secret_hash`), #2(major) `ClientLimitExceededException` 누락 보완, #3(minor) 검증 순서 번호 4·4→4·5, P1 `(B안)` 잔재 제거, P2 응답 예시 `201 Created` 표기.

## 변경 요약
- **신규 파일(코드, 클린업 후 잔존)**:
  - `services/libs/service-client/.../application/service/RouteNamespaceExtractor.java`
  - `services/libs/service-client/.../application/service/RouteValidator.java`
  - `services/libs/service-client/.../exception/RouteNamespaceInvalidException.java`
  - `services/libs/service-client/.../exception/RouteNamespaceTakenException.java`
  - `db/migration/V11__add_owner_id_to_service_route.sql`
  - `db/migration/V12__add_indexes_to_service_route.sql`
- **수정 파일(코드)**:
  - service-client: `RegisterOAuthClientUseCase`, `RegisterOAuthClientService`, `ServiceRoute`, `ServiceRouteRepository`, `ManageRouteService`, `ServiceRouteJpaEntity`, `ServiceRouteJpaRepository`, `ServiceRouteRepositoryAdapter`
  - auth-api: `ApplicationServiceConfig`, `GlobalExceptionHandler`, `ClientController`, `ClientApiDocs`, `SelfRegisterClientRequest`, `SelfRegisterClientResponse`
- **수정 파일(테스트)**:
  - `ClientControllerTest`, `RegisterOAuthClientServiceTest`, `ManageRouteServiceTest`, `ServiceRouteRepositoryAdapterTest`, `RouteNamespaceExtractorTest`
- **갱신 docs**: CLIENT_REGISTRATION.md, DYNAMIC_ROUTING.md, ARCHITECTURE.md, service-client/README.md, auth-api/README.md

## 최종 API 계약
- `POST /api/v1/clients` (회원 셀프, `@PassportAuth` memberId 필수):
  - 요청: `clientName`, `redirectUris` (필수) + `pathPrefix`, `upstreamUrl` (선택, 쌍).
  - 둘 다 non-blank → 클라이언트 + `service_route`(owner_id=memberId, enabled=true) 같은 트랜잭션 생성 + afterCommit 게이트웨이 refresh.
  - 둘 다 없음 → 클라이언트만. 한쪽만 → 400 `VALIDATION_FAILED` (DTO `@AssertTrue`, field=`routeFields`).
  - 응답(201): `clientId`, `clientSecret`, `routeId`, `pathPrefix`, `upstreamUrl`, `enabled` (라우트 미생성 시 명시적 null).
  - 라우트 에러: `ROUTE_NAMESPACE_INVALID`(400) → `ROUTE_NAMESPACE_TAKEN`(403) → `ROUTE_UPSTREAM_INVALID`(400) → `ROUTE_PROTECTED`(403) / `ROUTE_PATH_CONFLICT`(409). 전부 기존 라우트 에러코드 재사용.
  - 원자성: 라우트 검증 실패 시 클라이언트도 롤백.
- 네임스페이스: `/api/{namespace}/...` 두 번째 세그먼트. 선점 시 같은 owner만 재사용, 타 owner 거부. owner_id nullable(어드민=NULL).
- 어드민 `/api/v1/admin/routes` CRUD는 변경 없이 유지.

## plan과의 차이
1. **설계 전면 전환**: 초기 plan(라우트 CRUD 5개)을 폐기하고 클라이언트 등록 흡수로 plan 4종을 재작성. (대화 중 사용자 기획 변경)
2. **owner_id nullable 유지**: NOT NULL 검토했으나 nullable 유지(어드민=NULL).
3. **`findOwnerIdByNamespace` → `findOwnerIdsByNamespace`(List)**: 코드리뷰에서 어드민(null owner) 라우트 공존 시 복수결과 위험 발견 → List 반환 + null 필터로 안전화.

## 범위 밖 / 후속
- **ADR 신규 작성 권장**: 이번 결정(클라이언트 등록에 라우트 흡수 + 네임스페이스 선점 + service_route.owner_id)은 ADR 감. `/adr`로 별도 작성 권장.
- **클라이언트 목록/상세 조회 API(추후)**: 라우팅 정보 조회는 이 API가 담당 예정(이번 범위 밖).
- **내부망 hostname 허용**: 셀프 등록 라우트도 어드민과 동일하게 DNS 미조회 hostname 허용 유지(에코노 회원 전제). 좁히는 옵션은 추후 ADR 재검토 조건.
- **FE 변경 필요**: 클라이언트 등록 시 라우트 필드 선택 입력.

## 다음 단계
- /commit 으로 커밋
- /git-pr 로 PR 생성
- (권장) /adr 로 설계 결정 기록
