---
name: jpa-patterns
description: Spring Boot에서 엔티티 설계, 연관관계, 쿼리 최적화, 트랜잭션, 감사(auditing), 인덱싱, 페이지네이션, 커넥션 풀링을 다루는 JPA/Hibernate 패턴.
origin: ECC
---

# JPA/Hibernate 패턴

Spring Boot에서 데이터 모델링, 리포지토리, 성능 튜닝에 사용한다.

## 활성화 시점

- JPA 엔티티와 테이블 매핑 설계
- 연관관계 정의 (@OneToMany, @ManyToOne, @ManyToMany)
- 쿼리 최적화 (N+1 방지, fetch 전략, 프로젝션)
- 트랜잭션, 감사(auditing), 소프트 삭제 설정
- 페이지네이션, 정렬, 커스텀 리포지토리 메서드 구성
- 커넥션 풀링(HikariCP) 또는 2차 캐시 튜닝

## 엔티티 설계

```java
@Entity
@Table(name = "markets", indexes = {
  @Index(name = "idx_markets_slug", columnList = "slug", unique = true)
})
@EntityListeners(AuditingEntityListener.class)
public class MarketEntity {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 200)
  private String name;

  @Column(nullable = false, unique = true, length = 120)
  private String slug;

  @Enumerated(EnumType.STRING)
  private MarketStatus status = MarketStatus.ACTIVE;

  @CreatedDate private Instant createdAt;
  @LastModifiedDate private Instant updatedAt;
}
```

감사(auditing) 활성화:
```java
@Configuration
@EnableJpaAuditing
class JpaConfig {}
```

## 연관관계와 N+1 방지

```java
@OneToMany(mappedBy = "market", cascade = CascadeType.ALL, orphanRemoval = true)
private List<PositionEntity> positions = new ArrayList<>();
```

- 기본은 지연 로딩으로 두고, 필요 시 쿼리에서 `JOIN FETCH`를 사용한다
- 컬렉션에는 `EAGER`를 피한다. 읽기 경로에는 DTO 프로젝션을 사용한다

```java
@Query("select m from MarketEntity m left join fetch m.positions where m.id = :id")
Optional<MarketEntity> findWithPositions(@Param("id") Long id);
```

## 리포지토리 패턴

```java
public interface MarketRepository extends JpaRepository<MarketEntity, Long> {
  Optional<MarketEntity> findBySlug(String slug);

  @Query("select m from MarketEntity m where m.status = :status")
  Page<MarketEntity> findByStatus(@Param("status") MarketStatus status, Pageable pageable);
}
```

- 가벼운 쿼리에는 프로젝션을 사용한다:
```java
public interface MarketSummary {
  Long getId();
  String getName();
  MarketStatus getStatus();
}
Page<MarketSummary> findAllBy(Pageable pageable);
```

## 트랜잭션

- 서비스 메서드에 `@Transactional`을 부여한다
- 읽기 경로에는 `@Transactional(readOnly = true)`를 사용해 최적화한다
- 전파(propagation)는 신중히 선택한다. 장기 트랜잭션은 피한다

```java
@Transactional
public Market updateStatus(Long id, MarketStatus status) {
  MarketEntity entity = repo.findById(id)
      .orElseThrow(() -> new EntityNotFoundException("Market"));
  entity.setStatus(status);
  return Market.from(entity);
}
```

## 페이지네이션

```java
PageRequest page = PageRequest.of(pageNumber, pageSize, Sort.by("createdAt").descending());
Page<MarketEntity> markets = repo.findByStatus(MarketStatus.ACTIVE, page);
```

커서 형태의 페이지네이션은 JPQL에 정렬과 함께 `id > :lastId`를 포함한다.

## 인덱싱과 성능

- 자주 사용하는 필터(`status`, `slug`, 외래 키)에 인덱스를 추가한다
- 쿼리 패턴에 맞는 복합 인덱스를 사용한다 (`status, created_at`)
- `select *`를 피한다. 필요한 컬럼만 프로젝션한다
- `saveAll`과 `hibernate.jdbc.batch_size`로 쓰기를 배치 처리한다

## 커넥션 풀링 (HikariCP)

권장 설정:
```
spring.datasource.hikari.maximum-pool-size=20
spring.datasource.hikari.minimum-idle=5
spring.datasource.hikari.connection-timeout=30000
spring.datasource.hikari.validation-timeout=5000
```

PostgreSQL LOB 처리를 위해 추가한다:
```
spring.jpa.properties.hibernate.jdbc.lob.non_contextual_creation=true
```

## 캐싱

- 1차 캐시는 EntityManager 단위다. 트랜잭션을 가로질러 엔티티를 보관하지 않는다
- 읽기 위주 엔티티에는 2차 캐시를 신중히 검토한다. 무효화 전략을 반드시 검증한다

## 마이그레이션

- Flyway 또는 Liquibase를 사용한다. 운영에서 Hibernate auto DDL에 의존하지 않는다
- 마이그레이션은 멱등하고 가산적으로 유지한다. 계획 없이 컬럼을 드롭하지 않는다

## 데이터 접근 테스트

- 운영 환경을 모사하기 위해 `@DataJpaTest`와 Testcontainers를 함께 사용한다
- 로그로 SQL 효율을 검증한다: `logging.level.org.hibernate.SQL=DEBUG`로 설정하고, 파라미터 값까지 보려면 `logging.level.org.hibernate.orm.jdbc.bind=TRACE`를 추가한다

**기억할 것**: 엔티티는 가볍게, 쿼리는 의도적으로, 트랜잭션은 짧게 유지한다. fetch 전략과 프로젝션으로 N+1을 방지하고, 읽기/쓰기 경로에 맞춰 인덱싱한다.
