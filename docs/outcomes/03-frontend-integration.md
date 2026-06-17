# 프론트엔드가 얻는 것

프론트엔드(React, Next.js 등) 관점에서 auth-common 인증 시스템을 사용했을 때 **실제로 무엇이 가능해지는지** 정리한다.

---

## 핵심: 쿠키가 모든 걸 처리한다

로그인 성공 후 브라우저가 `at` / `rt` HttpOnly 쿠키를 받으면, 이후 모든 API 요청에서 브라우저가 **자동으로 쿠키를 포함**시킨다. 프론트엔드가 토큰을 localStorage에 저장하거나 헤더에 직접 담을 필요가 없다.

```
Set-Cookie: at=<JWT>; HttpOnly; SameSite=None; Secure; Domain=.econovation.kr
Set-Cookie: rt=<JWT>; HttpOnly; SameSite=None; Secure; Domain=.econovation.kr
```

---

## 회원 가입

```typescript
const res = await fetch('https://gateway.econovation.kr/api/v1/auth/signup', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    name: '홍길동',
    loginId: 'honggildong',
    password: 'Econo1234!',  // 대문자+소문자+숫자+특수기호 각 1자 이상, 8~19자
    generation: 30,
    status: 'AM'             // AM | RM | CM | OB
  })
})
// 201 Created → 성공
// 409 Conflict → loginId 중복
// 400 Bad Request → 비밀번호 정책 위반
```

---

## 로그인

```typescript
const res = await fetch('https://gateway.econovation.kr/api/v1/auth/login', {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'Client-Type': 'WEB'    // WEB이면 쿠키, APP이면 body로 토큰 수신
  },
  credentials: 'include',   // 쿠키 수신을 위해 필수
  body: JSON.stringify({ loginId: 'honggildong', password: 'Econo1234!' })
})

// 성공 응답 (WEB)
// { "redirectUrl": "https://..." }
// → at, rt 쿠키 자동 세팅됨

// 실패
// 401 → { "errorCode": "INVALID_CREDENTIALS" }
```

> `credentials: 'include'`를 반드시 설정해야 쿠키가 브라우저에 저장된다.

---

## 인증이 필요한 API 호출

로그인 후 모든 API 요청에 `credentials: 'include'`만 추가하면 된다. 토큰 관리는 브라우저가 한다.

```typescript
const res = await fetch('https://gateway.econovation.kr/api/some/resource', {
  credentials: 'include'  // at 쿠키 자동 포함
})
```

Gateway가 쿠키의 AT를 검증하고 내부 서비스에 전달한다.

---

## 토큰 재발급 (만료 처리)

AT가 만료되면 401이 온다. RT로 재발급 요청:

```typescript
async function reissue() {
  const res = await fetch('https://gateway.econovation.kr/api/v1/auth/reissue', {
    method: 'POST',
    headers: { 'Client-Type': 'WEB' },
    credentials: 'include'  // rt 쿠키 자동 포함
  })
  // 성공: 새 at, rt 쿠키 자동 세팅
  // 401: RT도 만료 → 로그인 페이지로 이동
}

// Axios interceptor 예시
axios.interceptors.response.use(
  response => response,
  async error => {
    if (error.response?.status === 401 && !error.config._retry) {
      error.config._retry = true
      await reissue()
      return axios(error.config)
    }
    return Promise.reject(error)
  }
)
```

---

## 로그아웃

```typescript
await fetch('https://gateway.econovation.kr/api/v1/auth/logout', {
  method: 'POST',
  headers: { 'Client-Type': 'WEB' },
  credentials: 'include'
})
// at, rt 쿠키 Max-Age=0으로 즉시 만료
// 멱등 — 이미 로그아웃 상태여도 200
```

---

## SSO: 다른 서비스로 이동해도 로그인 유지

`app-a.econovation.kr`에서 로그인 → `app-b.econovation.kr`로 이동 → **재로그인 필요 없음**

`Domain=.econovation.kr` 쿠키가 모든 서브도메인에 자동 전송되기 때문이다.

단, 다음 조건이 충족되어야 한다:
- **HTTPS 필수** (`SameSite=None; Secure` 조합은 HTTP에서 작동 안 함)
- `credentials: 'include'` 설정 필수
- CORS `allowCredentials: true` 설정 필수 (Gateway에서 처리)

---

## 에러 코드 정리

| HTTP | errorCode | 상황 |
|---|---|---|
| 401 | `INVALID_CREDENTIALS` | 아이디/비밀번호 불일치 |
| 401 | `REFRESH_TOKEN_MISSING` | RT 쿠키 없음 |
| 401 | `REFRESH_TOKEN_INVALID` | RT 만료 또는 AT를 RT 자리에 사용 |
| 409 | `MEMBER_ALREADY_EXISTS` | 이미 사용 중인 loginId |
| 400 | `INVALID_PASSWORD_POLICY` | 비밀번호 규칙 위반 |
| 400 | `VALIDATION_FAILED` | 필수 필드 누락 또는 형식 오류 |

---

## 체크리스트

- [x] 로그인 한 번으로 모든 `*.econovation.kr` 서비스 **SSO**
- [x] localStorage 토큰 저장 불필요 — **HttpOnly 쿠키로 XSS 방어**
- [x] 토큰 만료 시 RT로 **자동 재발급** 가능
- [x] `credentials: 'include'` 설정 한 줄로 **인증 완성**
- [x] 로그아웃 후 쿠키 즉시 만료 — **클라이언트 토큰 삭제 코드 불필요**
