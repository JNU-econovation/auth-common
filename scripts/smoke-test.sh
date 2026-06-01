#!/bin/bash
# auth-api + api-gateway 스모크 테스트
# 사용법: ./scripts/smoke-test.sh [AUTH_API_URL] [GATEWAY_URL]
# 실행 전 필수 환경변수:
#   AUTH_INTERNAL_API_KEY=<key>       — GET /api/v1/admin/routes 인증용
#   ADMIN_SESSION_COOKIE=<cookie>     — POST /api/v1/admin/clients 인증용 (로그인 후 발급)
# 예: AUTH_INTERNAL_API_KEY=my-key ADMIN_SESSION_COOKIE="JSESSIONID=xxx" ./scripts/smoke-test.sh

set -euo pipefail

AUTH_API_URL="${1:-http://localhost:8081}"
GATEWAY_URL="${2:-http://localhost:8080}"
PASS=0
FAIL=0
TOTAL=0

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

AUTH_INTERNAL_API_KEY="${AUTH_INTERNAL_API_KEY:-}"
ADMIN_SESSION_COOKIE="${ADMIN_SESSION_COOKIE:-}"

assert_status() {
  local test_name="$1" expected="$2" actual="$3" body="${4:-}"
  TOTAL=$((TOTAL + 1))
  if [ "$actual" -eq "$expected" ]; then
    echo -e "${GREEN}[PASS]${NC} $test_name (HTTP $actual)"
    PASS=$((PASS + 1))
  else
    echo -e "${RED}[FAIL]${NC} $test_name (expected $expected, got $actual)"
    [ -n "$body" ] && echo "       Response: $(echo "$body" | head -2)"
    FAIL=$((FAIL + 1))
  fi
}

assert_contains() {
  local test_name="$1" expected="$2" body="$3"
  TOTAL=$((TOTAL + 1))
  if echo "$body" | grep -q "$expected"; then
    echo -e "${GREEN}[PASS]${NC} $test_name (contains '$expected')"
    PASS=$((PASS + 1))
  else
    echo -e "${RED}[FAIL]${NC} $test_name (missing '$expected')"
    echo "       Response: $(echo "$body" | head -2)"
    FAIL=$((FAIL + 1))
  fi
}

echo "======================================"
echo " auth-common 스모크 테스트"
echo " AUTH_API_URL : $AUTH_API_URL"
echo " GATEWAY_URL  : $GATEWAY_URL"
echo "======================================"
echo ""

# ──────────────────────────────────────────────────────────────
# A. GET /api/v1/admin/routes — Internal API Key 인증
# ──────────────────────────────────────────────────────────────
echo "[ A. GET /api/v1/admin/routes ]"

if [ -z "$AUTH_INTERNAL_API_KEY" ]; then
  echo -e "${YELLOW}[SKIP]${NC} AUTH_INTERNAL_API_KEY 미설정 — export AUTH_INTERNAL_API_KEY=<key>"
  TOTAL=$((TOTAL + 3))
else
  # A-1. 정상 키 → 200 + routes 배열
  RESPONSE=$(curl -s -w "\n%{http_code}" \
    -H "X-Internal-Api-Key: $AUTH_INTERNAL_API_KEY" \
    "$AUTH_API_URL/api/v1/admin/routes")
  BODY=$(echo "$RESPONSE" | sed '$d')
  STATUS=$(echo "$RESPONSE" | tail -1)
  assert_status "A-1. 올바른 Internal API Key → 200" 200 "$STATUS" "$BODY"
  assert_contains "A-1. 응답에 routes 배열 존재" '"routes"' "$BODY"

  # A-2. 키 없음 → 401
  RESPONSE=$(curl -s -w "\n%{http_code}" "$AUTH_API_URL/api/v1/admin/routes")
  STATUS=$(echo "$RESPONSE" | tail -1)
  assert_status "A-2. Internal API Key 헤더 없음 → 401" 401 "$STATUS"

  # A-3. 잘못된 키 → 401
  RESPONSE=$(curl -s -w "\n%{http_code}" \
    -H "X-Internal-Api-Key: wrong-key-value" \
    "$AUTH_API_URL/api/v1/admin/routes")
  STATUS=$(echo "$RESPONSE" | tail -1)
  assert_status "A-3. 잘못된 Internal API Key → 401" 401 "$STATUS"
fi

echo ""

# ──────────────────────────────────────────────────────────────
# B. POST /api/v1/admin/clients — 클라이언트 등록 (세션 인증 필요)
# ──────────────────────────────────────────────────────────────
echo "[ B. POST /api/v1/admin/clients ]"

# B-1. 미인증 → 401
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST \
  -H "Content-Type: application/json" \
  -d '{"grantType":"authorization_code","clientName":"test","redirectUris":["http://localhost:3000/cb"]}' \
  "$AUTH_API_URL/api/v1/admin/clients")
STATUS=$(echo "$RESPONSE" | tail -1)
assert_status "B-1. 미인증 요청 → 401" 401 "$STATUS"

if [ -z "$ADMIN_SESSION_COOKIE" ]; then
  echo -e "${YELLOW}[SKIP]${NC} ADMIN 세션 쿠키 미설정 — 로그인 후 export ADMIN_SESSION_COOKIE='JSESSIONID=xxx'"
  TOTAL=$((TOTAL + 5))
else
  COOKIE_HEADER="Cookie: $ADMIN_SESSION_COOKIE"

  # B-2. authorization_code 등록 성공 → 201 + clientId
  RESPONSE=$(curl -s -w "\n%{http_code}" -X POST \
    -H "Content-Type: application/json" \
    -H "$COOKIE_HEADER" \
    -d '{"grantType":"authorization_code","clientName":"smoke-test-spa","redirectUris":["http://localhost:3000/callback"]}' \
    "$AUTH_API_URL/api/v1/admin/clients")
  BODY=$(echo "$RESPONSE" | sed '$d')
  STATUS=$(echo "$RESPONSE" | tail -1)
  assert_status "B-2. authorization_code 등록 → 201" 201 "$STATUS" "$BODY"
  assert_contains "B-2. 응답에 clientId 포함" '"clientId"' "$BODY"

  # B-3. client_credentials 등록 → 201 + clientSecret 포함
  RESPONSE=$(curl -s -w "\n%{http_code}" -X POST \
    -H "Content-Type: application/json" \
    -H "$COOKIE_HEADER" \
    -d '{"grantType":"client_credentials","clientName":"smoke-test-server","upstreamUrl":"http://eeos-server:8080","pathPrefix":"/api/eeos"}' \
    "$AUTH_API_URL/api/v1/admin/clients")
  BODY=$(echo "$RESPONSE" | sed '$d')
  STATUS=$(echo "$RESPONSE" | tail -1)
  assert_status "B-3. client_credentials 등록 → 201" 201 "$STATUS" "$BODY"
  assert_contains "B-3. 응답에 clientSecret 포함" '"clientSecret"' "$BODY"
  assert_contains "B-3. 응답에 routeId 포함" '"routeId"' "$BODY"

  # B-4. authorization_code인데 redirectUris 없음 → 400
  RESPONSE=$(curl -s -w "\n%{http_code}" -X POST \
    -H "Content-Type: application/json" \
    -H "$COOKIE_HEADER" \
    -d '{"grantType":"authorization_code","clientName":"no-redirect-client"}' \
    "$AUTH_API_URL/api/v1/admin/clients")
  STATUS=$(echo "$RESPONSE" | tail -1)
  assert_status "B-4. authorization_code + redirectUris 없음 → 400" 400 "$STATUS"

  # B-5. 지원하지 않는 grantType → 400
  RESPONSE=$(curl -s -w "\n%{http_code}" -X POST \
    -H "Content-Type: application/json" \
    -H "$COOKIE_HEADER" \
    -d '{"grantType":"implicit","clientName":"bad-grant"}' \
    "$AUTH_API_URL/api/v1/admin/clients")
  STATUS=$(echo "$RESPONSE" | tail -1)
  assert_status "B-5. 지원하지 않는 grantType → 400" 400 "$STATUS"

  # B-6. 중복 clientName → 409
  RESPONSE=$(curl -s -w "\n%{http_code}" -X POST \
    -H "Content-Type: application/json" \
    -H "$COOKIE_HEADER" \
    -d '{"grantType":"authorization_code","clientName":"smoke-test-spa","redirectUris":["http://localhost:3000/callback"]}' \
    "$AUTH_API_URL/api/v1/admin/clients")
  STATUS=$(echo "$RESPONSE" | tail -1)
  assert_status "B-6. 중복 clientName → 409" 409 "$STATUS"
fi

echo ""

# ──────────────────────────────────────────────────────────────
# C. Gateway 동적 라우팅 동작 확인 (auth-api + gateway 모두 기동 필요)
# ──────────────────────────────────────────────────────────────
echo "[ C. Gateway 동적 라우팅 ]"

# C-1. Gateway health 확인
RESPONSE=$(curl -s -w "\n%{http_code}" "$GATEWAY_URL/actuator/health" 2>/dev/null || echo -e "\n000")
STATUS=$(echo "$RESPONSE" | tail -1)
assert_status "C-1. Gateway 기동 확인 (actuator/health)" 200 "$STATUS"

echo ""
echo "======================================"
echo -e " 결과: ${GREEN}PASS=$PASS${NC} / ${RED}FAIL=$FAIL${NC} / TOTAL=$TOTAL"
echo "======================================"
echo ""
echo "서버 기동 방법:"
echo "  1. PostgreSQL 실행 (DB_URL, DB_USERNAME, DB_PASSWORD 설정)"
echo "  2. RSA 키 생성: openssl genrsa | openssl pkcs8 -topk8 -nocrypt > private.pem"
echo "  3. auth-api 기동: cd services/apis/auth-api && RSA_PRIVATE_KEY=... ./gradlew bootRun"
echo "  4. api-gateway 기동: cd services/apis/api-gateway && AUTH_INTERNAL_API_KEY=... ./gradlew bootRun"
echo "  5. 로그인: POST $AUTH_API_URL/api/v1/auth/login → JSESSIONID 쿠키 획득"
echo "  6. 재실행: ADMIN_SESSION_COOKIE='JSESSIONID=xxx' AUTH_INTERNAL_API_KEY=key ./scripts/smoke-test.sh"

if [ "$FAIL" -gt 0 ]; then
  exit 1
fi
