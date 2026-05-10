---
name: springboot-security
description: Java Spring Boot 서비스의 인증·인가, 검증, CSRF, 시크릿, 헤더, 속도 제한, 의존성 보안에 대한 Spring Security 모범 사례.
origin: ECC
---

# Spring Boot 보안 리뷰

인증을 추가하거나, 입력을 처리하거나, 엔드포인트를 만들거나, 시크릿을 다룰 때 사용한다.

## 호출 시점

- 인증 추가 (JWT, OAuth2, 세션 기반)
- 인가 구현 (@PreAuthorize, 역할 기반 접근 제어)
- 사용자 입력 검증 (Bean Validation, 커스텀 검증기)
- CORS, CSRF, 보안 헤더 구성
- 시크릿 관리 (Vault, 환경 변수)
- 속도 제한 또는 무차별 대입 방어 추가
- 의존성의 CVE 스캔

## 인증

- 가능하면 stateless JWT 또는 폐기 목록을 갖춘 opaque 토큰을 사용한다
- 세션에는 `httpOnly`, `Secure`, `SameSite=Strict` 쿠키를 사용한다
- `OncePerRequestFilter` 또는 리소스 서버로 토큰을 검증한다

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {
  private final JwtService jwtService;

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain chain) throws ServletException, IOException {
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (header != null && header.startsWith("Bearer ")) {
      String token = header.substring(7);
      Authentication auth = jwtService.authenticate(token);
      SecurityContextHolder.getContext().setAuthentication(auth);
    }
    chain.doFilter(request, response);
  }
}
```

## 인가

- 메서드 시큐리티 활성화: `@EnableMethodSecurity`
- `@PreAuthorize("hasRole('ADMIN')")` 또는 `@PreAuthorize("@authz.canEdit(#id)")`를 사용한다
- 기본은 거부, 필요한 스코프만 노출한다

```java
@RestController
@RequestMapping("/api/admin")
public class AdminController {

  @PreAuthorize("hasRole('ADMIN')")
  @GetMapping("/users")
  public List<UserDto> listUsers() {
    return userService.findAll();
  }

  @PreAuthorize("@authz.isOwner(#id, authentication)")
  @DeleteMapping("/users/{id}")
  public ResponseEntity<Void> deleteUser(@PathVariable Long id) {
    userService.delete(id);
    return ResponseEntity.noContent().build();
  }
}
```

## 입력 검증

- 컨트롤러에 `@Valid`로 Bean Validation을 적용한다
- DTO에 제약 조건을 둔다: `@NotBlank`, `@Email`, `@Size`, 커스텀 검증기
- HTML은 렌더링 전에 화이트리스트로 정제한다

```java
// 나쁜 예: 검증 없음
@PostMapping("/users")
public User createUser(@RequestBody UserDto dto) {
  return userService.create(dto);
}

// 좋은 예: 검증된 DTO
public record CreateUserDto(
    @NotBlank @Size(max = 100) String name,
    @NotBlank @Email String email,
    @NotNull @Min(0) @Max(150) Integer age
) {}

@PostMapping("/users")
public ResponseEntity<UserDto> createUser(@Valid @RequestBody CreateUserDto dto) {
  return ResponseEntity.status(HttpStatus.CREATED)
      .body(userService.create(dto));
}
```

## SQL 인젝션 방어

- Spring Data 리포지토리 또는 파라미터 바인딩 쿼리를 사용한다
- 네이티브 쿼리에는 `:param` 바인딩을 사용하고, 절대 문자열을 연결하지 않는다

```java
// 나쁜 예: 네이티브 쿼리에서 문자열 연결
@Query(value = "SELECT * FROM users WHERE name = '" + name + "'", nativeQuery = true)

// 좋은 예: 파라미터 바인딩 네이티브 쿼리
@Query(value = "SELECT * FROM users WHERE name = :name", nativeQuery = true)
List<User> findByName(@Param("name") String name);

// 좋은 예: Spring Data 파생 쿼리 (자동 파라미터화)
List<User> findByEmailAndActiveTrue(String email);
```

## 비밀번호 인코딩

- 비밀번호는 항상 BCrypt 또는 Argon2로 해싱한다 — 평문 저장 금지
- 직접 해싱하지 말고 `PasswordEncoder` 빈을 사용한다

```java
@Bean
public PasswordEncoder passwordEncoder() {
  return new BCryptPasswordEncoder(12); // 비용 인자 12
}

// 서비스에서
public User register(CreateUserDto dto) {
  String hashedPassword = passwordEncoder.encode(dto.password());
  return userRepository.save(new User(dto.email(), hashedPassword));
}
```

## CSRF 방어

- 브라우저 세션 앱은 CSRF를 활성화한 채 폼/헤더에 토큰을 포함한다
- Bearer 토큰을 사용하는 순수 API는 CSRF를 비활성화하고 stateless 인증에 의존한다

```java
http
  .csrf(csrf -> csrf.disable())
  .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
```

## 시크릿 관리

- 시크릿은 소스에 두지 않고 환경 변수나 vault에서 로드한다
- `application.yml`에 자격 증명을 두지 않고 플레이스홀더를 사용한다
- 토큰과 DB 자격 증명을 주기적으로 교체한다

```yaml
# 나쁜 예: application.yml에 하드코딩
spring:
  datasource:
    password: mySecretPassword123

# 좋은 예: 환경 변수 플레이스홀더
spring:
  datasource:
    password: ${DB_PASSWORD}

# 좋은 예: Spring Cloud Vault 통합
spring:
  cloud:
    vault:
      uri: https://vault.example.com
      token: ${VAULT_TOKEN}
```

## 보안 헤더

```java
http
  .headers(headers -> headers
    .contentSecurityPolicy(csp -> csp
      .policyDirectives("default-src 'self'"))
    .frameOptions(HeadersConfigurer.FrameOptionsConfig::sameOrigin)
    .xssProtection(Customizer.withDefaults())
    .referrerPolicy(rp -> rp.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)));
```

## CORS 구성

- CORS는 컨트롤러별이 아닌 보안 필터 수준에서 구성한다
- 허용 오리진을 제한한다 — 운영 환경에서 `*` 사용 금지

```java
@Bean
public CorsConfigurationSource corsConfigurationSource() {
  CorsConfiguration config = new CorsConfiguration();
  config.setAllowedOrigins(List.of("https://app.example.com"));
  config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE"));
  config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
  config.setAllowCredentials(true);
  config.setMaxAge(3600L);

  UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
  source.registerCorsConfiguration("/api/**", config);
  return source;
}

// SecurityFilterChain에서:
http.cors(cors -> cors.configurationSource(corsConfigurationSource()));
```

## 속도 제한

- 비용이 큰 엔드포인트에는 Bucket4j 또는 게이트웨이 수준의 제한을 적용한다
- 급증을 로깅·알림하고 재시도 힌트와 함께 429를 반환한다

```java
// Bucket4j를 사용한 엔드포인트별 속도 제한
@Component
public class RateLimitFilter extends OncePerRequestFilter {
  private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

  private Bucket createBucket() {
    return Bucket.builder()
        .addLimit(Bandwidth.classic(100, Refill.intervally(100, Duration.ofMinutes(1))))
        .build();
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain chain) throws ServletException, IOException {
    String clientIp = request.getRemoteAddr();
    Bucket bucket = buckets.computeIfAbsent(clientIp, k -> createBucket());

    if (bucket.tryConsume(1)) {
      chain.doFilter(request, response);
    } else {
      response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
      response.getWriter().write("{\"error\": \"Rate limit exceeded\"}");
    }
  }
}
```

## 의존성 보안

- CI에서 OWASP Dependency Check / Snyk를 실행한다
- Spring Boot와 Spring Security를 지원되는 버전으로 유지한다
- 알려진 CVE가 있으면 빌드를 실패시킨다

## 로깅과 PII

- 시크릿, 토큰, 비밀번호, 카드번호 전체를 로깅하지 않는다
- 민감 필드를 마스킹하고 구조화된 JSON 로깅을 사용한다

## 파일 업로드

- 크기, 콘텐츠 타입, 확장자를 검증한다
- 웹 루트 외부에 저장하고 필요 시 스캔한다

## 릴리스 전 체크리스트

- [ ] 인증 토큰이 올바르게 검증되고 만료된다
- [ ] 모든 민감 경로에 인가 가드가 적용되어 있다
- [ ] 모든 입력이 검증·정제된다
- [ ] 문자열 연결로 작성된 SQL이 없다
- [ ] 앱 유형에 맞는 CSRF 정책이 설정되어 있다
- [ ] 시크릿이 외부화되어 있고 커밋되지 않았다
- [ ] 보안 헤더가 구성되어 있다
- [ ] API에 속도 제한이 적용되어 있다
- [ ] 의존성을 스캔했고 최신 상태이다
- [ ] 로그에 민감 데이터가 없다

**기억할 점**: 기본은 거부, 입력은 검증, 최소 권한, 그리고 안전한 기본 구성을 우선한다.
