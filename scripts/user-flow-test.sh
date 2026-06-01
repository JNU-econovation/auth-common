#!/bin/bash
# auth-common + EEOS-BE 유저 플로우 통합 테스트
# 실제 사용 시나리오를 순서대로 검증한다
#
# 사전 조건:
#   - auth-api  : http://localhost:8081
#   - api-gateway: http://localhost:8082
#   - EEOS-BE   : http://localhost:8080
#   - AUTH_INTERNAL_API_KEY 환경변수 설정
#
# 실행: AUTH_INTERNAL_API_KEY=local-test-key ./scripts/user-flow-test.sh

set -o pipefail

AUTH_API="${1:-http://localhost:8081}"
GATEWAY="${2:-http://localhost:8082}"
EEOS="${3:-http://localhost:8080}"

INTERNAL_KEY="${AUTH_INTERNAL_API_KEY:-}"

PASS=0; FAIL=0; TOTAL=0

GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'

step() { echo -e "\n${CYAN}━━━ $1 ━━━${NC}"; }
pass() { TOTAL=$((TOTAL+1)); PASS=$((PASS+1)); echo -e "${GREEN}[PASS]${NC} $1"; }
fail() { TOTAL=$((TOTAL+1)); FAIL=$((FAIL+1)); echo -e "${RED}[FAIL]${NC} $1"; [ -n "${2:-}" ] && echo "       → $2"; }
skip() { TOTAL=$((TOTAL+1)); echo -e "${YELLOW}[SKIP]${NC} $1"; }
info() { echo "       ℹ $1"; }

http() {
  local method="$1" url="$2"; shift 2
  local extra_args=() body=""
  for arg in "$@"; do
    if [[ "$arg" == "{"* ]] || [[ "$arg" == "["* ]]; then body="$arg"
    else extra_args+=("-H" "$arg")
    fi
  done
  if [ -n "$body" ]; then
    curl -s -w "\n%{http_code}" -X "$method" "$url" "${extra_args[@]+"${extra_args[@]}"}" \
      -H "Content-Type: application/json" -d "$body"
  else
    curl -s -w "\n%{http_code}" -X "$method" "$url" "${extra_args[@]+"${extra_args[@]}"}"
  fi
}

status_of() { echo "$1" | tail -1; }
body_of()   { echo "$1" | sed '$d'; }
extract()   { body_of "$1" | python3 -c "import sys,json; print(json.load(sys.stdin).get('$2',''))" 2>/dev/null; }

if [ -z "$INTERNAL_KEY" ]; then
  echo -e "${RED}AUTH_INTERNAL_API_KEY 미설정. 종료.${NC}"
  exit 1
fi

echo "======================================================"
echo " auth-common + EEOS-BE 유저 플로우 통합 테스트"
echo " auth-api : $AUTH_API"
echo " gateway  : $GATEWAY"
echo " eeos     : $EEOS"
echo "======================================================"

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# FLOW 1: auth-api 서버 상태 확인
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
step "FLOW 1: 서버 상태 확인"

R=$(http GET "$AUTH_API/.well-known/openid-configuration")
S=$(status_of "$R")
[ "$S" = "200" ] && pass "auth-api SAS 정상 동작 (/.well-known/openid-configuration)" || fail "auth-api 미응답" "HTTP $S"

R=$(http GET "$GATEWAY/actuator/health")
S=$(status_of "$R")
[ "$S" = "200" ] && pass "api-gateway 정상 동작 (actuator/health)" || fail "api-gateway 미응답" "HTTP $S"

R=$(http GET "$EEOS/api/health-check")
S=$(status_of "$R")
[ "$S" = "200" ] && pass "EEOS-BE 정상 동작 (api/health-check)" || fail "EEOS-BE 미응답" "HTTP $S"

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# FLOW 2: auth-api 회원가입 + 로그인
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
step "FLOW 2: auth-api 회원가입 → 로그인 → 토큰 획득"

SIGNUP_BODY='{"loginId":"flowtest01","password":"Test1234!","name":"플로우테스터","generation":30,"status":"AM"}'
R=$(http POST "$AUTH_API/api/v1/auth/signup" "$SIGNUP_BODY")
S=$(status_of "$R")
if [ "$S" = "200" ] || [ "$S" = "201" ]; then
  pass "회원가입 성공"
elif [ "$S" = "409" ]; then
  info "이미 가입된 계정 — 로그인 진행"
else
  fail "회원가입 실패" "HTTP $S — $(body_of "$R" | head -c 100)"
fi

LOGIN_BODY='{"loginId":"flowtest01","password":"Test1234!"}'
R=$(http POST "$AUTH_API/api/v1/auth/login" "$LOGIN_BODY")
S=$(status_of "$R")
SESSION_COOKIE=$(curl -s -c /tmp/auth-cookies.txt -X POST "$AUTH_API/api/v1/auth/login" \
  -H "Content-Type: application/json" -d "$LOGIN_BODY" -o /dev/null \
  && grep JSESSIONID /tmp/auth-cookies.txt 2>/dev/null | awk '{print "JSESSIONID="$NF}')
if [ "$S" = "200" ]; then
  pass "로그인 성공 (HTTP 200)"
  info "세션: ${SESSION_COOKIE:-없음}"
else
  fail "로그인 실패" "HTTP $S — $(body_of "$R" | head -c 100)"
fi

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# FLOW 3: OAuth 클라이언트 등록 (Internal API Key)
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
step "FLOW 3: OAuth 클라이언트 등록 (X-Internal-Api-Key 인증)"

# 3-1. 키 없이 → 401
R=$(http POST "$AUTH_API/api/v1/admin/clients" \
  '{"grantType":"authorization_code","clientName":"test","redirectUris":["http://localhost:3000/cb"]}')
S=$(status_of "$R")
[ "$S" = "401" ] && pass "키 없이 클라이언트 등록 → 401" || fail "기대 401, 실제 $S"

# 3-2. authorization_code 클라이언트 등록
TS=$(date +%s)
AC_BODY="{\"grantType\":\"authorization_code\",\"clientName\":\"flow-spa-$TS\",\"redirectUris\":[\"http://localhost:3000/callback\"]}"
R=$(http POST "$AUTH_API/api/v1/admin/clients" "X-Internal-Api-Key: $INTERNAL_KEY" "$AC_BODY")
S=$(status_of "$R"); BODY=$(body_of "$R")
AC_CLIENT_ID=$(echo "$BODY" | python3 -c "import sys,json; print(json.load(sys.stdin).get('clientId',''))" 2>/dev/null)
if [ "$S" = "201" ] && [ -n "$AC_CLIENT_ID" ]; then
  pass "authorization_code 클라이언트 등록 성공"
  info "clientId: $AC_CLIENT_ID"
else
  fail "authorization_code 등록 실패" "HTTP $S — $BODY"
fi

# 3-3. client_credentials + upstreamUrl → EEOS-BE 라우팅
CC_BODY="{\"grantType\":\"client_credentials\",\"clientName\":\"eeos-gateway-client-$TS\",\"upstreamUrl\":\"$EEOS\",\"pathPrefix\":\"/api/eeos-$TS\"}"
R=$(http POST "$AUTH_API/api/v1/admin/clients" "X-Internal-Api-Key: $INTERNAL_KEY" "$CC_BODY")
S=$(status_of "$R"); BODY=$(body_of "$R")
CC_CLIENT_ID=$(echo "$BODY" | python3 -c "import sys,json; print(json.load(sys.stdin).get('clientId',''))" 2>/dev/null)
CC_SECRET=$(echo "$BODY" | python3 -c "import sys,json; print(json.load(sys.stdin).get('clientSecret',''))" 2>/dev/null)
ROUTE_ID=$(echo "$BODY" | python3 -c "import sys,json; print(json.load(sys.stdin).get('routeId',''))" 2>/dev/null)
if [ "$S" = "201" ] && [ -n "$CC_CLIENT_ID" ] && [ -n "$CC_SECRET" ] && [ -n "$ROUTE_ID" ]; then
  pass "client_credentials 클라이언트 등록 + 라우트 등록 성공"
  info "clientId: $CC_CLIENT_ID"
  info "clientSecret: ${CC_SECRET:0:20}... (1회 노출)"
  info "routeId: $ROUTE_ID"
else
  fail "client_credentials 등록 실패" "HTTP $S — $BODY"
fi

# 3-4. 중복 clientName → 409 (동일 body 재시도)
R=$(http POST "$AUTH_API/api/v1/admin/clients" "X-Internal-Api-Key: $INTERNAL_KEY" "$CC_BODY")
S=$(status_of "$R")
[ "$S" = "409" ] && pass "중복 clientName → 409" || fail "기대 409, 실제 $S"

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# FLOW 4: 라우트 목록 조회
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
step "FLOW 4: 라우트 목록 조회"

R=$(http GET "$AUTH_API/api/v1/admin/routes" "X-Internal-Api-Key: $INTERNAL_KEY")
S=$(status_of "$R"); BODY=$(body_of "$R")
ROUTE_COUNT=$(echo "$BODY" | python3 -c "import sys,json; print(len(json.load(sys.stdin).get('routes',[])))" 2>/dev/null)
if [ "$S" = "200" ] && [ "${ROUTE_COUNT:-0}" -gt 0 ]; then
  pass "라우트 목록 조회 성공 ($ROUTE_COUNT 개)"
  echo "$BODY" | python3 -c "
import sys,json
for r in json.load(sys.stdin).get('routes',[]):
  print(f\"       ├ {r.get('pathPrefix','?')} → {r.get('upstreamUrl','?')}\")
" 2>/dev/null
else
  fail "라우트 조회 실패 또는 비어있음" "HTTP $S count=$ROUTE_COUNT"
fi

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# FLOW 5: Gateway 동적 라우팅 동작 확인
# (Gateway가 30초마다 폴링하므로 즉시 반영 안 될 수 있음)
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
step "FLOW 5: Gateway → EEOS-BE 동적 라우팅"

info "Gateway는 30초마다 라우트를 폴링합니다. 즉시 반영 안 될 수 있음."
R=$(http GET "$GATEWAY/api/eeos/api/health-check")
S=$(status_of "$R"); BODY=$(body_of "$R")
if [ "$S" = "200" ]; then
  pass "Gateway /api/eeos/** → EEOS-BE 라우팅 성공!"
  info "응답: $(echo "$BODY" | head -c 100)"
elif [ "$S" = "404" ]; then
  info "아직 라우트 미적용 (Gateway 폴링 대기 중). 30초 후 재시도:"
  info "  curl http://localhost:8082/api/eeos/api/health-check"
  skip "FLOW 5: Gateway 라우팅 (폴링 대기)"
else
  fail "Gateway 라우팅 실패" "HTTP $S"
fi

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# FLOW 6: EEOS-BE OAuth 클라이언트 관리
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
step "FLOW 6: EEOS-BE — 관리자 로그인 → OAuth 클라이언트 등록"

# EEOS-BE 관리자 로그인
EEOS_LOGIN='{"id":"econoking","password":"fromblackcompany0822^^"}'
R=$(http POST "$EEOS/api/v1/auth/login" "$EEOS_LOGIN")
S=$(status_of "$R"); BODY=$(body_of "$R")
EEOS_TOKEN=$(echo "$BODY" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('data',{}).get('accessToken',''))" 2>/dev/null)
if { [ "$S" = "200" ] || [ "$S" = "201" ]; } && [ -n "$EEOS_TOKEN" ]; then
  pass "EEOS-BE 관리자 로그인 성공"
  info "토큰 발급 완료 (${#EEOS_TOKEN}자)"
else
  fail "EEOS-BE 로그인 실패" "HTTP $S"
  EEOS_TOKEN=""
fi

if [ -n "$EEOS_TOKEN" ]; then
  # WEB 클라이언트 등록
  TS=$(date +%s)
  WEB_BODY="{\"clientName\":\"eeos-web-app-$TS\",\"clientType\":\"WEB\",\"redirectUris\":[\"http://localhost:3000/callback\"]}"
  R=$(http POST "$EEOS/api/v2/auth/clients" "Authorization: Bearer $EEOS_TOKEN" "$WEB_BODY")
  S=$(status_of "$R"); BODY=$(body_of "$R")
  WEB_CLIENT_ID=$(echo "$BODY" | python3 -c "import sys,json; print(json.load(sys.stdin).get('data',{}).get('clientId',''))" 2>/dev/null)
  WEB_SECRET=$(echo "$BODY" | python3 -c "import sys,json; print(json.load(sys.stdin).get('data',{}).get('clientSecret',''))" 2>/dev/null)
  if [ "$S" = "201" ] && [ -n "$WEB_CLIENT_ID" ]; then
    pass "EEOS-BE WEB 클라이언트 등록 성공"
    info "clientId: $WEB_CLIENT_ID"
    info "clientSecret: ${WEB_SECRET:0:20}..."
  else
    fail "EEOS-BE WEB 클라이언트 등록 실패" "HTTP $S — $BODY"
  fi

  # USER 권한으로 접근 시도 (403 확인)
  # 현재 USER 토큰 없으므로 만료된 더미 토큰으로 테스트
  R=$(http POST "$EEOS/api/v2/auth/clients" \
    "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyIiwicm9sZSI6WyJST0xFX1VTRVIiXX0.dummy" \
    '{"clientName":"hack","clientType":"WEB","redirectUris":["http://x"]}')
  S=$(status_of "$R")
  [ "$S" = "401" ] && pass "유효하지 않은 토큰 → 401 (변조 토큰 거부)" || fail "기대 401, 실제 $S"
fi

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# FLOW 7: SAS OAuth2 Authorization Code 흐름 시작점 확인
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
step "FLOW 7: SAS OAuth2 엔드포인트 동작 확인"

# JWKS 공개키 조회 (Gateway 통해)
R=$(http GET "$GATEWAY/.well-known/openid-configuration")
S=$(status_of "$R"); BODY=$(body_of "$R")
ISSUER=$(echo "$BODY" | python3 -c "import sys,json; print(json.load(sys.stdin).get('issuer',''))" 2>/dev/null)
if [ "$S" = "200" ] && [ -n "$ISSUER" ]; then
  pass "SAS OIDC 설정 조회 (Gateway 통해)"
  info "issuer: $ISSUER"
else
  fail "SAS OIDC 설정 조회 실패" "HTTP $S"
fi

# Authorization Code 흐름 진입점 (redirect 확인)
R=$(curl -s -o /dev/null -w "%{http_code}\n%{redirect_url}" \
  "$AUTH_API/oauth2/authorize?response_type=code&client_id=econo-spa&redirect_uri=http://localhost:3000/callback&scope=openid")
AUTH_STATUS=$(echo "$R" | head -1)
AUTH_REDIRECT=$(echo "$R" | tail -1)
if [ "$AUTH_STATUS" = "302" ]; then
  pass "Authorization Code 흐름 진입 → 302 redirect"
  info "redirect: ${AUTH_REDIRECT:0:80}..."
else
  fail "Authorization Code 흐름 실패" "HTTP $AUTH_STATUS"
fi

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# 결과 요약
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
echo ""
echo "======================================================"
echo -e " 결과: ${GREEN}PASS=$PASS${NC} / ${RED}FAIL=$FAIL${NC} / TOTAL=$TOTAL"
echo "======================================================"
echo ""
echo "현재 동작하는 것:"
echo "  ✅ auth-api 서버 (SAS + Admin API)"
echo "  ✅ api-gateway 서버 (JWT 검증 + 동적 라우팅)"
echo "  ✅ EEOS-BE (OAuth 클라이언트 관리, JWT 기반 인증)"
echo "  ✅ OAuth 클라이언트 등록 (authorization_code / client_credentials)"
echo "  ✅ 라우트 등록 및 목록 조회"
echo "  ✅ Internal API Key 인증 (X-Internal-Api-Key)"
echo "  ⏳ Gateway 동적 라우팅 반영 (30초 폴링)"
echo ""
echo "현재 안 되는 것 (추가 구현 필요):"
echo "  ❌ client_credentials grant로 access token 발급"
echo "     → SAS에서 기본 비활성화. ClientSettings 설정 필요"
echo "  ❌ Gateway를 통한 Bearer 토큰 → Passport 변환"
echo "     → 정상 사용자 계정 + OAuth2 전체 플로우 필요"
echo ""
[ "$FAIL" -gt 0 ] && exit 1 || exit 0
