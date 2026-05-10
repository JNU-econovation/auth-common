---
name: docker-patterns
description: 로컬 개발, 컨테이너 보안, 네트워킹, 볼륨 전략, 멀티 서비스 오케스트레이션을 위한 Docker 및 Docker Compose 패턴.
origin: ECC
---

# Docker Patterns

컨테이너 기반 개발을 위한 Docker 및 Docker Compose 모범 사례.

## 호출 시점

- 로컬 개발용 Docker Compose 구성
- 멀티 컨테이너 아키텍처 설계
- 컨테이너 네트워킹 또는 볼륨 문제 디버깅
- Dockerfile 보안·이미지 크기 리뷰
- 로컬 개발에서 컨테이너 기반 워크플로로 전환

## 로컬 개발용 Docker Compose

### 표준 웹 앱 스택

```yaml
# docker-compose.yml
services:
  app:
    build:
      context: .
      target: dev                     # 멀티 스테이지 Dockerfile의 dev 스테이지 사용
    ports:
      - "3000:3000"
    volumes:
      - .:/app                        # 핫 리로드를 위한 바인드 마운트
      - /app/node_modules             # 익명 볼륨 -- 컨테이너 의존성 보존
    environment:
      - DATABASE_URL=postgres://postgres:postgres@db:5432/app_dev
      - REDIS_URL=redis://redis:6379/0
      - NODE_ENV=development
    depends_on:
      db:
        condition: service_healthy
      redis:
        condition: service_started
    command: npm run dev

  db:
    image: postgres:16-alpine
    ports:
      - "5432:5432"
    environment:
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
      POSTGRES_DB: app_dev
    volumes:
      - pgdata:/var/lib/postgresql/data
      - ./scripts/init-db.sql:/docker-entrypoint-initdb.d/init.sql
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres"]
      interval: 5s
      timeout: 3s
      retries: 5

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
    volumes:
      - redisdata:/data

  mailpit:                            # 로컬 이메일 테스트
    image: axllent/mailpit
    ports:
      - "8025:8025"                   # 웹 UI
      - "1025:1025"                   # SMTP

volumes:
  pgdata:
  redisdata:
```

### 개발용 vs 운영용 Dockerfile

```dockerfile
# 스테이지: 의존성 설치
FROM node:22-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

# 스테이지: dev (핫 리로드, 디버그 도구)
FROM node:22-alpine AS dev
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .
EXPOSE 3000
CMD ["npm", "run", "dev"]

# 스테이지: 빌드
FROM node:22-alpine AS build
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .
RUN npm run build && npm prune --production

# 스테이지: 운영 (최소 이미지)
FROM node:22-alpine AS production
WORKDIR /app
RUN addgroup -g 1001 -S appgroup && adduser -S appuser -u 1001
USER appuser
COPY --from=build --chown=appuser:appgroup /app/dist ./dist
COPY --from=build --chown=appuser:appgroup /app/node_modules ./node_modules
COPY --from=build --chown=appuser:appgroup /app/package.json ./
ENV NODE_ENV=production
EXPOSE 3000
HEALTHCHECK --interval=30s --timeout=3s CMD wget -qO- http://localhost:3000/health || exit 1
CMD ["node", "dist/server.js"]
```

### Override 파일

```yaml
# docker-compose.override.yml (자동 로드, 개발 전용 설정)
services:
  app:
    environment:
      - DEBUG=app:*
      - LOG_LEVEL=debug
    ports:
      - "9229:9229"                   # Node.js 디버거

# docker-compose.prod.yml (운영용은 명시적으로 지정)
services:
  app:
    build:
      target: production
    restart: always
    deploy:
      resources:
        limits:
          cpus: "1.0"
          memory: 512M
```

```bash
# 개발 (override 자동 로드)
docker compose up

# 운영
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d
```

## 네트워킹

### 서비스 디스커버리

같은 Compose 네트워크에 속한 서비스는 서비스 이름으로 해석된다:
```
# "app" 컨테이너에서:
postgres://postgres:postgres@db:5432/app_dev    # "db"는 db 컨테이너로 해석
redis://redis:6379/0                             # "redis"는 redis 컨테이너로 해석
```

### 커스텀 네트워크

```yaml
services:
  frontend:
    networks:
      - frontend-net

  api:
    networks:
      - frontend-net
      - backend-net

  db:
    networks:
      - backend-net              # api에서만 접근 가능, frontend에서는 불가

networks:
  frontend-net:
  backend-net:
```

### 필요한 포트만 노출

```yaml
services:
  db:
    ports:
      - "127.0.0.1:5432:5432"   # 호스트에서만 접근 가능, 외부 네트워크에서는 불가
    # 운영에서는 ports를 아예 생략 -- Docker 네트워크 내부에서만 접근 가능
```

## 볼륨 전략

```yaml
volumes:
  # 명명 볼륨(named volume): 컨테이너 재시작 간 영속, Docker가 관리
  pgdata:

  # 바인드 마운트: 호스트 디렉터리를 컨테이너에 매핑 (개발용)
  # - ./src:/app/src

  # 익명 볼륨: 바인드 마운트 오버라이드로부터 컨테이너 생성 콘텐츠 보존
  # - /app/node_modules
```

### 일반 패턴

```yaml
services:
  app:
    volumes:
      - .:/app                   # 소스 코드 (핫 리로드용 바인드 마운트)
      - /app/node_modules        # 호스트로부터 컨테이너의 node_modules 보호
      - /app/.next               # 빌드 캐시 보호

  db:
    volumes:
      - pgdata:/var/lib/postgresql/data          # 영속 데이터
      - ./scripts/init.sql:/docker-entrypoint-initdb.d/init.sql  # 초기화 스크립트
```

## 컨테이너 보안

### Dockerfile 강화

```dockerfile
# 1. 명시적 태그 사용 (절대 :latest 금지)
FROM node:22.12-alpine3.20

# 2. non-root로 실행
RUN addgroup -g 1001 -S app && adduser -S app -u 1001
USER app

# 3. capability 제거 (compose에서)
# 4. 가능하면 read-only 루트 파일시스템
# 5. 이미지 레이어에 시크릿 포함 금지
```

### Compose 보안

```yaml
services:
  app:
    security_opt:
      - no-new-privileges:true
    read_only: true
    tmpfs:
      - /tmp
      - /app/.cache
    cap_drop:
      - ALL
    cap_add:
      - NET_BIND_SERVICE          # 1024 미만 포트 바인딩이 필요할 때만
```

### 시크릿 관리

```yaml
# GOOD: 환경 변수 사용 (런타임에 주입)
services:
  app:
    env_file:
      - .env                     # .env 파일은 절대 git에 커밋하지 않는다
    environment:
      - API_KEY                  # 호스트 환경에서 상속

# GOOD: Docker secrets (Swarm 모드)
secrets:
  db_password:
    file: ./secrets/db_password.txt

services:
  db:
    secrets:
      - db_password

# BAD: 이미지에 하드코딩
# ENV API_KEY=sk-proj-xxxxx      # 절대 금지
```

## .dockerignore

```
node_modules
.git
.env
.env.*
dist
coverage
*.log
.next
.cache
docker-compose*.yml
Dockerfile*
README.md
tests/
```

## 디버깅

### 자주 쓰는 명령

```bash
# 로그 보기
docker compose logs -f app           # app 로그 follow
docker compose logs --tail=50 db     # db의 마지막 50줄

# 실행 중인 컨테이너에서 명령 실행
docker compose exec app sh           # app 셸 접속
docker compose exec db psql -U postgres  # postgres 접속

# 검사
docker compose ps                     # 실행 중 서비스
docker compose top                    # 각 컨테이너의 프로세스
docker stats                          # 리소스 사용량

# 재빌드
docker compose up --build             # 이미지 재빌드
docker compose build --no-cache app   # 강제 전체 재빌드

# 정리
docker compose down                   # 컨테이너 중지·제거
docker compose down -v                # 볼륨까지 제거 (파괴적)
docker system prune                   # 사용하지 않는 이미지·컨테이너 제거
```

### 네트워크 문제 디버깅

```bash
# 컨테이너 내부에서 DNS 해석 확인
docker compose exec app nslookup db

# 연결 확인
docker compose exec app wget -qO- http://api:3000/health

# 네트워크 검사
docker network ls
docker network inspect <project>_default
```

## 안티패턴

```
# BAD: 오케스트레이션 없이 운영에서 docker compose 사용
# 운영용 멀티 컨테이너 워크로드는 Kubernetes, ECS, Docker Swarm을 사용한다

# BAD: 볼륨 없이 컨테이너 안에 데이터 저장
# 컨테이너는 휘발성 -- 볼륨이 없으면 재시작 시 모든 데이터 손실

# BAD: root로 실행
# 항상 non-root 사용자를 만들고 사용한다

# BAD: :latest 태그 사용
# 재현 가능한 빌드를 위해 명시적 버전을 고정한다

# BAD: 모든 서비스를 하나의 거대한 컨테이너에
# 관심사를 분리한다: 컨테이너 하나당 프로세스 하나

# BAD: docker-compose.yml에 시크릿 저장
# .env 파일(gitignore)이나 Docker secrets를 사용한다
```
