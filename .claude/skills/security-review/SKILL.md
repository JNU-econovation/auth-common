---
name: security-review
description: 인증 추가, 사용자 입력 처리, 시크릿 다루기, API 엔드포인트 생성, 결제·민감 기능 구현 시 사용한다. 종합 보안 체크리스트와 패턴을 제공한다.
origin: ECC
---

# 보안 리뷰 스킬

이 스킬은 모든 코드가 보안 베스트 프랙티스를 따르고 잠재적 취약점을 식별하도록 보장한다.

## 활성화 시점

- 인증·인가 구현
- 사용자 입력 또는 파일 업로드 처리
- 새 API 엔드포인트 생성
- 시크릿·자격 증명 작업
- 결제 기능 구현
- 민감한 데이터의 저장·전송
- 서드파티 API 통합

## 보안 체크리스트

### 1. 시크릿 관리

#### FAIL: 절대 이렇게 하지 않는다
```typescript
const apiKey = "sk-proj-xxxxx"  // 하드코딩된 시크릿
const dbPassword = "password123" // 소스 코드에 포함
```

#### PASS: 항상 이렇게 한다
```typescript
const apiKey = process.env.OPENAI_API_KEY
const dbUrl = process.env.DATABASE_URL

// 시크릿 존재 여부 검증
if (!apiKey) {
  throw new Error('OPENAI_API_KEY not configured')
}
```

#### 검증 단계
- [ ] 하드코딩된 API 키, 토큰, 비밀번호 없음
- [ ] 모든 시크릿이 환경 변수에 있음
- [ ] `.env.local`이 .gitignore에 포함됨
- [ ] git 히스토리에 시크릿 없음
- [ ] 운영 시크릿은 호스팅 플랫폼(Vercel, Railway)에 저장

### 2. 입력 검증

#### 사용자 입력은 항상 검증한다
```typescript
import { z } from 'zod'

// 검증 스키마 정의
const CreateUserSchema = z.object({
  email: z.string().email(),
  name: z.string().min(1).max(100),
  age: z.number().int().min(0).max(150)
})

// 처리 전에 검증
export async function createUser(input: unknown) {
  try {
    const validated = CreateUserSchema.parse(input)
    return await db.users.create(validated)
  } catch (error) {
    if (error instanceof z.ZodError) {
      return { success: false, errors: error.errors }
    }
    throw error
  }
}
```

#### 파일 업로드 검증
```typescript
function validateFileUpload(file: File) {
  // 크기 검사 (최대 5MB)
  const maxSize = 5 * 1024 * 1024
  if (file.size > maxSize) {
    throw new Error('File too large (max 5MB)')
  }

  // 타입 검사
  const allowedTypes = ['image/jpeg', 'image/png', 'image/gif']
  if (!allowedTypes.includes(file.type)) {
    throw new Error('Invalid file type')
  }

  // 확장자 검사
  const allowedExtensions = ['.jpg', '.jpeg', '.png', '.gif']
  const extension = file.name.toLowerCase().match(/\.[^.]+$/)?.[0]
  if (!extension || !allowedExtensions.includes(extension)) {
    throw new Error('Invalid file extension')
  }

  return true
}
```

#### 검증 단계
- [ ] 모든 사용자 입력을 스키마로 검증
- [ ] 파일 업로드 제한(크기, 타입, 확장자)
- [ ] 사용자 입력을 쿼리에 직접 사용하지 않음
- [ ] 화이트리스트 검증(블랙리스트 아님)
- [ ] 에러 메시지가 민감 정보를 노출하지 않음

### 3. SQL 인젝션 방지

#### FAIL: 절대 SQL을 문자열 연결하지 않는다
```typescript
// 위험 - SQL 인젝션 취약점
const query = `SELECT * FROM users WHERE email = '${userEmail}'`
await db.query(query)
```

#### PASS: 항상 파라미터화된 쿼리를 사용한다
```typescript
// 안전 - 파라미터화된 쿼리
const { data } = await supabase
  .from('users')
  .select('*')
  .eq('email', userEmail)

// 또는 raw SQL로
await db.query(
  'SELECT * FROM users WHERE email = $1',
  [userEmail]
)
```

#### 검증 단계
- [ ] 모든 데이터베이스 쿼리가 파라미터화됨
- [ ] SQL에 문자열 연결 없음
- [ ] ORM/쿼리 빌더를 올바르게 사용
- [ ] Supabase 쿼리가 적절히 새니타이즈됨

### 4. 인증과 인가

#### JWT 토큰 처리
```typescript
// FAIL: 잘못됨: localStorage (XSS에 취약)
localStorage.setItem('token', token)

// PASS: 올바름: httpOnly 쿠키
res.setHeader('Set-Cookie',
  `token=${token}; HttpOnly; Secure; SameSite=Strict; Max-Age=3600`)
```

#### 인가 검사
```typescript
export async function deleteUser(userId: string, requesterId: string) {
  // 민감한 작업 전 항상 인가를 검증
  const requester = await db.users.findUnique({
    where: { id: requesterId }
  })

  if (requester.role !== 'admin') {
    return NextResponse.json(
      { error: 'Unauthorized' },
      { status: 403 }
    )
  }

  // 삭제 진행
  await db.users.delete({ where: { id: userId } })
}
```

#### Row Level Security (Supabase)
```sql
-- 모든 테이블에 RLS 활성화
ALTER TABLE users ENABLE ROW LEVEL SECURITY;

-- 사용자는 자기 데이터만 조회 가능
CREATE POLICY "Users view own data"
  ON users FOR SELECT
  USING (auth.uid() = id);

-- 사용자는 자기 데이터만 수정 가능
CREATE POLICY "Users update own data"
  ON users FOR UPDATE
  USING (auth.uid() = id);
```

#### 검증 단계
- [ ] 토큰을 httpOnly 쿠키에 저장 (localStorage 금지)
- [ ] 민감 작업 전에 인가 검사
- [ ] Supabase에서 Row Level Security 활성화
- [ ] 역할 기반 접근 제어(RBAC) 구현
- [ ] 세션 관리가 안전함

### 5. XSS 방지

#### HTML 새니타이즈
```typescript
import DOMPurify from 'isomorphic-dompurify'

// 사용자 제공 HTML은 항상 새니타이즈
function renderUserContent(html: string) {
  const clean = DOMPurify.sanitize(html, {
    ALLOWED_TAGS: ['b', 'i', 'em', 'strong', 'p'],
    ALLOWED_ATTR: []
  })
  return <div dangerouslySetInnerHTML={{ __html: clean }} />
}
```

#### Content Security Policy
```typescript
// next.config.js
const securityHeaders = [
  {
    key: 'Content-Security-Policy',
    value: `
      default-src 'self';
      script-src 'self' 'unsafe-eval' 'unsafe-inline';
      style-src 'self' 'unsafe-inline';
      img-src 'self' data: https:;
      font-src 'self';
      connect-src 'self' https://api.example.com;
    `.replace(/\s{2,}/g, ' ').trim()
  }
]
```

#### 검증 단계
- [ ] 사용자 제공 HTML 새니타이즈됨
- [ ] CSP 헤더 구성됨
- [ ] 검증되지 않은 동적 콘텐츠 렌더링 없음
- [ ] React 내장 XSS 보호 활용

### 6. CSRF 보호

#### CSRF 토큰
```typescript
import { csrf } from '@/lib/csrf'

export async function POST(request: Request) {
  const token = request.headers.get('X-CSRF-Token')

  if (!csrf.verify(token)) {
    return NextResponse.json(
      { error: 'Invalid CSRF token' },
      { status: 403 }
    )
  }

  // 요청 처리
}
```

#### SameSite 쿠키
```typescript
res.setHeader('Set-Cookie',
  `session=${sessionId}; HttpOnly; Secure; SameSite=Strict`)
```

#### 검증 단계
- [ ] 상태 변경 작업에 CSRF 토큰 적용
- [ ] 모든 쿠키에 SameSite=Strict 적용
- [ ] Double-submit cookie 패턴 구현

### 7. 레이트 리미팅

#### API 레이트 리미팅
```typescript
import rateLimit from 'express-rate-limit'

const limiter = rateLimit({
  windowMs: 15 * 60 * 1000, // 15분
  max: 100, // 윈도우당 100 요청
  message: 'Too many requests'
})

// 라우트에 적용
app.use('/api/', limiter)
```

#### 비용이 큰 작업
```typescript
// 검색에는 더 강한 레이트 리미팅
const searchLimiter = rateLimit({
  windowMs: 60 * 1000, // 1분
  max: 10, // 분당 10 요청
  message: 'Too many search requests'
})

app.use('/api/search', searchLimiter)
```

#### 검증 단계
- [ ] 모든 API 엔드포인트에 레이트 리미팅
- [ ] 비용이 큰 작업에는 더 엄격한 제한
- [ ] IP 기반 레이트 리미팅
- [ ] 사용자 기반 레이트 리미팅(인증된 경우)

### 8. 민감 데이터 노출

#### 로깅
```typescript
// FAIL: 잘못됨: 민감 데이터 로깅
console.log('User login:', { email, password })
console.log('Payment:', { cardNumber, cvv })

// PASS: 올바름: 민감 데이터 마스킹
console.log('User login:', { email, userId })
console.log('Payment:', { last4: card.last4, userId })
```

#### 에러 메시지
```typescript
// FAIL: 잘못됨: 내부 세부사항 노출
catch (error) {
  return NextResponse.json(
    { error: error.message, stack: error.stack },
    { status: 500 }
  )
}

// PASS: 올바름: 일반 에러 메시지
catch (error) {
  console.error('Internal error:', error)
  return NextResponse.json(
    { error: 'An error occurred. Please try again.' },
    { status: 500 }
  )
}
```

#### 검증 단계
- [ ] 비밀번호, 토큰, 시크릿이 로그에 없음
- [ ] 사용자에게 노출되는 에러 메시지는 일반적
- [ ] 상세 에러는 서버 로그에만 기록
- [ ] 사용자에게 스택 트레이스 노출 금지

### 9. 블록체인 보안 (Solana)

#### 지갑 검증
```typescript
import { verify } from '@solana/web3.js'

async function verifyWalletOwnership(
  publicKey: string,
  signature: string,
  message: string
) {
  try {
    const isValid = verify(
      Buffer.from(message),
      Buffer.from(signature, 'base64'),
      Buffer.from(publicKey, 'base64')
    )
    return isValid
  } catch (error) {
    return false
  }
}
```

#### 트랜잭션 검증
```typescript
async function verifyTransaction(transaction: Transaction) {
  // 수신자 검증
  if (transaction.to !== expectedRecipient) {
    throw new Error('Invalid recipient')
  }

  // 금액 검증
  if (transaction.amount > maxAmount) {
    throw new Error('Amount exceeds limit')
  }

  // 사용자가 충분한 잔액을 가졌는지 검증
  const balance = await getBalance(transaction.from)
  if (balance < transaction.amount) {
    throw new Error('Insufficient balance')
  }

  return true
}
```

#### 검증 단계
- [ ] 지갑 서명 검증
- [ ] 트랜잭션 세부사항 검증
- [ ] 트랜잭션 전 잔액 검사
- [ ] 블라인드 트랜잭션 서명 금지

### 10. 의존성 보안

#### 정기 업데이트
```bash
# 취약점 확인
npm audit

# 자동 수정 가능한 이슈 수정
npm audit fix

# 의존성 업데이트
npm update

# 오래된 패키지 확인
npm outdated
```

#### Lock 파일
```bash
# 항상 lock 파일을 커밋
git add package-lock.json

# 재현 가능한 빌드를 위해 CI/CD에서 사용
npm ci  # npm install 대신
```

#### 검증 단계
- [ ] 의존성이 최신 상태
- [ ] 알려진 취약점 없음 (npm audit clean)
- [ ] Lock 파일 커밋됨
- [ ] GitHub에서 Dependabot 활성화
- [ ] 정기적인 보안 업데이트

## 보안 테스트

### 자동화된 보안 테스트
```typescript
// 인증 테스트
test('requires authentication', async () => {
  const response = await fetch('/api/protected')
  expect(response.status).toBe(401)
})

// 인가 테스트
test('requires admin role', async () => {
  const response = await fetch('/api/admin', {
    headers: { Authorization: `Bearer ${userToken}` }
  })
  expect(response.status).toBe(403)
})

// 입력 검증 테스트
test('rejects invalid input', async () => {
  const response = await fetch('/api/users', {
    method: 'POST',
    body: JSON.stringify({ email: 'not-an-email' })
  })
  expect(response.status).toBe(400)
})

// 레이트 리미팅 테스트
test('enforces rate limits', async () => {
  const requests = Array(101).fill(null).map(() =>
    fetch('/api/endpoint')
  )

  const responses = await Promise.all(requests)
  const tooManyRequests = responses.filter(r => r.status === 429)

  expect(tooManyRequests.length).toBeGreaterThan(0)
})
```

## 배포 전 보안 체크리스트

운영 배포 전에 반드시 확인한다:

- [ ] **시크릿**: 하드코딩 없음, 모두 환경 변수로
- [ ] **입력 검증**: 모든 사용자 입력 검증됨
- [ ] **SQL 인젝션**: 모든 쿼리가 파라미터화됨
- [ ] **XSS**: 사용자 콘텐츠 새니타이즈됨
- [ ] **CSRF**: 보호 활성화됨
- [ ] **인증**: 적절한 토큰 처리
- [ ] **인가**: 역할 검사 적용
- [ ] **레이트 리미팅**: 모든 엔드포인트에 활성화
- [ ] **HTTPS**: 운영에서 강제됨
- [ ] **보안 헤더**: CSP, X-Frame-Options 구성
- [ ] **에러 처리**: 에러에 민감 데이터 없음
- [ ] **로깅**: 민감 데이터 로그 금지
- [ ] **의존성**: 최신, 취약점 없음
- [ ] **Row Level Security**: Supabase에서 활성화
- [ ] **CORS**: 적절히 구성됨
- [ ] **파일 업로드**: 검증됨(크기, 타입)
- [ ] **지갑 서명**: 검증됨(블록체인 사용 시)

## 참고자료

- [OWASP Top 10](https://owasp.org/www-project-top-ten/)
- [Next.js Security](https://nextjs.org/docs/security)
- [Supabase Security](https://supabase.com/docs/guides/auth)
- [Web Security Academy](https://portswigger.net/web-security)

---

**기억할 것**: 보안은 선택이 아니다. 단 하나의 취약점이 전체 플랫폼을 손상시킬 수 있다. 의심스러우면 보수적으로 판단한다.
