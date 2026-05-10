---
name: springboot-patterns
description: Spring Boot 아키텍처 패턴, REST API 설계, 계층형 서비스, 데이터 접근, 캐싱, 비동기 처리, 로깅을 다룬다. Java Spring Boot 백엔드 작업에 사용한다.
origin: ECC
---

# Spring Boot 개발 패턴

확장 가능하고 운영 환경 수준의 서비스를 위한 Spring Boot 아키텍처 및 API 패턴.

## 호출 시점

- Spring MVC 또는 WebFlux로 REST API를 구축할 때
- controller → service → repository 계층 구조를 잡을 때
- Spring Data JPA, 캐싱, 비동기 처리를 설정할 때
- 검증, 예외 처리, 페이지네이션을 추가할 때
- dev/staging/production 환경을 위한 프로파일을 구성할 때
- Spring Events 또는 Kafka로 이벤트 기반 패턴을 구현할 때

## REST API 구조

```java
@RestController
@RequestMapping("/api/markets")
@Validated
@RequiredArgsConstructor
class MarketController {
  private final MarketService marketService;

  @GetMapping
  ResponseEntity<Page<MarketResponse>> list(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    Page<Market> markets = marketService.list(PageRequest.of(page, size));
    return ResponseEntity.ok(markets.map(MarketResponse::from));
  }

  @PostMapping
  ResponseEntity<MarketResponse> create(@Valid @RequestBody CreateMarketRequest request) {
    Market market = marketService.create(request);
    return ResponseEntity.status(HttpStatus.CREATED).body(MarketResponse.from(market));
  }
}
```

## Repository 패턴 (Spring Data JPA)

```java
public interface MarketRepository extends JpaRepository<MarketEntity, Long> {
  @Query("select m from MarketEntity m where m.status = :status order by m.volume desc")
  List<MarketEntity> findActive(@Param("status") MarketStatus status, Pageable pageable);
}
```

## 트랜잭션을 적용한 Service 계층

```java
@Service
@RequiredArgsConstructor
public class MarketService {
  private final MarketRepository repo;

  @Transactional
  public Market create(CreateMarketRequest request) {
    MarketEntity entity = MarketEntity.from(request);
    MarketEntity saved = repo.save(entity);
    return Market.from(saved);
  }
}
```

## DTO와 검증

```java
public record CreateMarketRequest(
    @NotBlank @Size(max = 200) String name,
    @NotBlank @Size(max = 2000) String description,
    @NotNull @FutureOrPresent Instant endDate,
    @NotEmpty List<@NotBlank String> categories) {}

public record MarketResponse(Long id, String name, MarketStatus status) {
  static MarketResponse from(Market market) {
    return new MarketResponse(market.id(), market.name(), market.status());
  }
}
```

## 예외 처리

```java
@ControllerAdvice
class GlobalExceptionHandler {
  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
    String message = ex.getBindingResult().getFieldErrors().stream()
        .map(e -> e.getField() + ": " + e.getDefaultMessage())
        .collect(Collectors.joining(", "));
    return ResponseEntity.badRequest().body(ApiError.validation(message));
  }

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<ApiError> handleAccessDenied() {
    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiError.of("Forbidden"));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiError> handleGeneric(Exception ex) {
    // 예상치 못한 오류는 스택 트레이스와 함께 로깅한다
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(ApiError.of("Internal server error"));
  }
}
```

## 캐싱

설정 클래스에 `@EnableCaching`이 필요하다.

```java
@Service
@RequiredArgsConstructor
public class MarketCacheService {
  private final MarketRepository repo;

  @Cacheable(value = "market", key = "#id")
  public Market getById(Long id) {
    return repo.findById(id)
        .map(Market::from)
        .orElseThrow(() -> new EntityNotFoundException("Market not found"));
  }

  @CacheEvict(value = "market", key = "#id")
  public void evict(Long id) {}
}
```

## 비동기 처리

설정 클래스에 `@EnableAsync`가 필요하다.

```java
@Service
public class NotificationService {
  @Async
  public CompletableFuture<Void> sendAsync(Notification notification) {
    // 이메일/SMS 발송
    return CompletableFuture.completedFuture(null);
  }
}
```

## 로깅 (SLF4J + Lombok)

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

  public Report generate(Long marketId) {
    log.info("generate_report marketId={}", marketId);
    try {
      // 로직
    } catch (Exception ex) {
      log.error("generate_report_failed marketId={}", marketId, ex);
      throw ex;
    }
    return new Report();
  }
}
```

## 미들웨어 / 필터

```java
@Slf4j
@Component
public class RequestLoggingFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    long start = System.currentTimeMillis();
    try {
      filterChain.doFilter(request, response);
    } finally {
      long duration = System.currentTimeMillis() - start;
      log.info("req method={} uri={} status={} durationMs={}",
          request.getMethod(), request.getRequestURI(), response.getStatus(), duration);
    }
  }
}
```

## 페이지네이션과 정렬

```java
PageRequest page = PageRequest.of(pageNumber, pageSize, Sort.by("createdAt").descending());
Page<Market> results = marketService.list(page);
```

## 외부 호출의 오류 복원성

```java
public <T> T withRetry(Supplier<T> supplier, int maxRetries) {
  int attempts = 0;
  while (true) {
    try {
      return supplier.get();
    } catch (Exception ex) {
      attempts++;
      if (attempts >= maxRetries) {
        throw ex;
      }
      try {
        Thread.sleep((long) Math.pow(2, attempts) * 100L);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        throw ex;
      }
    }
  }
}
```

## 속도 제한 (Filter + Bucket4j)

**보안 주의**: `X-Forwarded-For` 헤더는 클라이언트가 위조할 수 있으므로 기본적으로 신뢰할 수 없다.
forwarded 헤더는 다음 조건이 모두 충족될 때만 사용한다:
1. 애플리케이션이 신뢰할 수 있는 리버스 프록시(nginx, AWS ALB 등) 뒤에 있다
2. `ForwardedHeaderFilter`를 빈으로 등록했다
3. 애플리케이션 프로퍼티에 `server.forward-headers-strategy=NATIVE` 또는 `FRAMEWORK`를 설정했다
4. 프록시가 `X-Forwarded-For` 헤더를 덮어쓰도록(append하지 않도록) 구성되어 있다

`ForwardedHeaderFilter`가 올바르게 구성되면 `request.getRemoteAddr()`가 forwarded 헤더에서
실제 클라이언트 IP를 자동으로 반환한다. 이러한 구성이 없다면 `request.getRemoteAddr()`를 직접 사용한다.
이는 직접 연결된 IP를 반환하며, 신뢰할 수 있는 유일한 값이다.

```java
@Component
public class RateLimitFilter extends OncePerRequestFilter {
  private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

  /*
   * 보안: 이 필터는 속도 제한을 위해 클라이언트를 식별할 때 request.getRemoteAddr()를 사용한다.
   *
   * 애플리케이션이 리버스 프록시(nginx, AWS ALB 등) 뒤에 있다면, 정확한 클라이언트 IP 감지를 위해
   * Spring이 forwarded 헤더를 올바르게 처리하도록 반드시 구성해야 한다:
   *
   * 1. application.properties/yaml에 server.forward-headers-strategy=NATIVE (클라우드 플랫폼) 또는
   *    FRAMEWORK 를 설정한다
   * 2. FRAMEWORK 전략을 사용하는 경우 ForwardedHeaderFilter를 등록한다:
   *
   *    @Bean
   *    ForwardedHeaderFilter forwardedHeaderFilter() {
   *        return new ForwardedHeaderFilter();
   *    }
   *
   * 3. 위조 방지를 위해 프록시가 X-Forwarded-For 헤더를 append가 아닌 덮어쓰도록 구성한다
   * 4. 컨테이너에 맞춰 server.tomcat.remoteip.trusted-proxies 또는 동등한 설정을 구성한다
   *
   * 이 구성이 없다면 request.getRemoteAddr()는 클라이언트 IP가 아닌 프록시 IP를 반환한다.
   * X-Forwarded-For를 직접 읽지 않는다 — 신뢰할 수 있는 프록시 처리 없이는 손쉽게 위조될 수 있다.
   */
  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    // ForwardedHeaderFilter가 구성되어 있으면 올바른 클라이언트 IP를, 그렇지 않으면 직접 연결된 IP를
    // 반환하는 getRemoteAddr()를 사용한다. 적절한 프록시 구성 없이 X-Forwarded-For 헤더를
    // 직접 신뢰하지 않는다.
    String clientIp = request.getRemoteAddr();

    Bucket bucket = buckets.computeIfAbsent(clientIp,
        k -> Bucket.builder()
            .addLimit(Bandwidth.classic(100, Refill.greedy(100, Duration.ofMinutes(1))))
            .build());

    if (bucket.tryConsume(1)) {
      filterChain.doFilter(request, response);
    } else {
      response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    }
  }
}
```

## 백그라운드 작업

Spring의 `@Scheduled`를 사용하거나 큐(Kafka, SQS, RabbitMQ 등)와 통합한다. 핸들러는 멱등성을 유지하고 관측 가능하게 만든다.

## 관측성

- Logback 인코더를 통한 구조화 로깅(JSON)
- 메트릭: Micrometer + Prometheus/OTel
- 트레이싱: OpenTelemetry 또는 Brave 백엔드와 함께 사용하는 Micrometer Tracing

## 운영 기본값

- 필드 주입을 피하고 Lombok `@RequiredArgsConstructor`로 생성자 주입을 사용한다
- RFC 7807 오류를 위해 `spring.mvc.problemdetails.enabled=true`를 활성화한다 (Spring Boot 3+)
- 워크로드에 맞춰 HikariCP 풀 크기를 구성하고 타임아웃을 설정한다
- 조회 메서드에는 `@Transactional(readOnly = true)`를 사용한다
- 적절한 곳에 `@NonNull`과 `Optional`로 null 안전성을 강제한다

**기억할 점**: 컨트롤러는 얇게, 서비스는 책임을 명확하게, 리포지토리는 단순하게 유지하고 오류는 중앙에서 처리한다. 유지보수성과 테스트 용이성을 우선 최적화한다.
