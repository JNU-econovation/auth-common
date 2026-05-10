| name | description |
|------|-------------|
| cloud-infrastructure-security | 클라우드 플랫폼 배포, 인프라 구성, IAM 정책 관리, 로깅·모니터링 셋업, CI/CD 파이프라인 구현 시 사용한다. 베스트 프랙티스에 부합하는 클라우드 보안 체크리스트를 제공한다. |

# 클라우드·인프라 보안 스킬

이 스킬은 클라우드 인프라, CI/CD 파이프라인, 배포 구성이 보안 베스트 프랙티스를 따르고 업계 표준을 준수하도록 보장한다.

## 활성화 시점

- 클라우드 플랫폼(AWS, Vercel, Railway, Cloudflare)에 애플리케이션 배포
- IAM 역할과 권한 구성
- CI/CD 파이프라인 구축
- IaC(Terraform, CloudFormation) 구현
- 로깅과 모니터링 구성
- 클라우드 환경의 시크릿 관리
- CDN과 엣지 보안 구성
- 재해 복구·백업 전략 구현

## 클라우드 보안 체크리스트

### 1. IAM과 접근 제어

#### 최소 권한 원칙

```yaml
# PASS: 올바름: 최소 권한
iam_role:
  permissions:
    - s3:GetObject  # 읽기 전용 접근
    - s3:ListBucket
  resources:
    - arn:aws:s3:::my-bucket/*  # 특정 버킷만

# FAIL: 잘못됨: 지나치게 넓은 권한
iam_role:
  permissions:
    - s3:*  # 모든 S3 액션
  resources:
    - "*"  # 모든 리소스
```

#### 다단계 인증(MFA)

```bash
# 루트/관리자 계정에는 항상 MFA를 활성화
aws iam enable-mfa-device \
  --user-name admin \
  --serial-number arn:aws:iam::123456789:mfa/admin \
  --authentication-code1 123456 \
  --authentication-code2 789012
```

#### 검증 단계

- [ ] 운영에서 루트 계정 사용 금지
- [ ] 모든 권한 계정에 MFA 활성화
- [ ] 서비스 계정은 장기 자격 증명 대신 역할 사용
- [ ] IAM 정책이 최소 권한을 따름
- [ ] 정기 접근 검토 수행
- [ ] 미사용 자격 증명 회전 또는 제거

### 2. 시크릿 관리

#### 클라우드 시크릿 매니저

```typescript
// PASS: 올바름: 클라우드 시크릿 매니저 사용
import { SecretsManager } from '@aws-sdk/client-secrets-manager';

const client = new SecretsManager({ region: 'us-east-1' });
const secret = await client.getSecretValue({ SecretId: 'prod/api-key' });
const apiKey = JSON.parse(secret.SecretString).key;

// FAIL: 잘못됨: 하드코딩 또는 환경 변수만 사용
const apiKey = process.env.API_KEY; // 회전·감사 안 됨
```

#### 시크릿 회전

```bash
# 데이터베이스 자격 증명 자동 회전 설정
aws secretsmanager rotate-secret \
  --secret-id prod/db-password \
  --rotation-lambda-arn arn:aws:lambda:region:account:function:rotate \
  --rotation-rules AutomaticallyAfterDays=30
```

#### 검증 단계

- [ ] 모든 시크릿이 클라우드 시크릿 매니저(AWS Secrets Manager, Vercel Secrets)에 저장됨
- [ ] 데이터베이스 자격 증명 자동 회전 활성화
- [ ] API 키는 최소 분기마다 회전
- [ ] 코드, 로그, 에러 메시지에 시크릿 없음
- [ ] 시크릿 접근에 대한 감사 로깅 활성화

### 3. 네트워크 보안

#### VPC와 방화벽 구성

```terraform
# PASS: 올바름: 제한된 보안 그룹
resource "aws_security_group" "app" {
  name = "app-sg"

  ingress {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["10.0.0.0/16"]  # 내부 VPC만
  }

  egress {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]  # HTTPS 아웃바운드만
  }
}

# FAIL: 잘못됨: 인터넷에 개방
resource "aws_security_group" "bad" {
  ingress {
    from_port   = 0
    to_port     = 65535
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]  # 모든 포트, 모든 IP!
  }
}
```

#### 검증 단계

- [ ] 데이터베이스가 공개적으로 접근 불가
- [ ] SSH/RDP 포트는 VPN/배스천만 허용
- [ ] 보안 그룹이 최소 권한을 따름
- [ ] 네트워크 ACL 구성됨
- [ ] VPC 플로우 로그 활성화

### 4. 로깅과 모니터링

#### CloudWatch/로깅 구성

```typescript
// PASS: 올바름: 종합 로깅
import { CloudWatchLogsClient, CreateLogStreamCommand } from '@aws-sdk/client-cloudwatch-logs';

const logSecurityEvent = async (event: SecurityEvent) => {
  await cloudwatch.putLogEvents({
    logGroupName: '/aws/security/events',
    logStreamName: 'authentication',
    logEvents: [{
      timestamp: Date.now(),
      message: JSON.stringify({
        type: event.type,
        userId: event.userId,
        ip: event.ip,
        result: event.result,
        // 민감 데이터는 절대 로그에 남기지 않는다
      })
    }]
  });
};
```

#### 검증 단계

- [ ] 모든 서비스에 CloudWatch/로깅 활성화
- [ ] 인증 실패 시도 기록
- [ ] 관리자 작업 감사
- [ ] 로그 보존 기간 설정(컴플라이언스 90일 이상)
- [ ] 의심스러운 활동에 대한 알림 구성
- [ ] 로그 중앙화·변조 방지

### 5. CI/CD 파이프라인 보안

#### 안전한 파이프라인 구성

```yaml
# PASS: 올바름: 안전한 GitHub Actions 워크플로
name: Deploy

on:
  push:
    branches: [main]

jobs:
  deploy:
    runs-on: ubuntu-latest
    permissions:
      contents: read  # 최소 권한

    steps:
      - uses: actions/checkout@v4

      # 시크릿 스캔
      - name: Secret scanning
        uses: trufflesecurity/trufflehog@main

      # 의존성 감사
      - name: Audit dependencies
        run: npm audit --audit-level=high

      # 장기 토큰 대신 OIDC 사용
      - name: Configure AWS credentials
        uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: arn:aws:iam::123456789:role/GitHubActionsRole
          aws-region: us-east-1
```

#### 공급망 보안

```json
// package.json - lock 파일과 무결성 검사 사용
{
  "scripts": {
    "install": "npm ci",  // 재현 가능한 빌드를 위해 ci 사용
    "audit": "npm audit --audit-level=moderate",
    "check": "npm outdated"
  }
}
```

#### 검증 단계

- [ ] 장기 자격 증명 대신 OIDC 사용
- [ ] 파이프라인에서 시크릿 스캐닝
- [ ] 의존성 취약점 스캐닝
- [ ] 컨테이너 이미지 스캐닝(해당 시)
- [ ] 브랜치 보호 규칙 적용
- [ ] 머지 전 코드 리뷰 필수
- [ ] 서명된 커밋 강제

### 6. Cloudflare와 CDN 보안

#### Cloudflare 보안 구성

```typescript
// PASS: 올바름: 보안 헤더가 포함된 Cloudflare Workers
export default {
  async fetch(request: Request): Promise<Response> {
    const response = await fetch(request);

    // 보안 헤더 추가
    const headers = new Headers(response.headers);
    headers.set('X-Frame-Options', 'DENY');
    headers.set('X-Content-Type-Options', 'nosniff');
    headers.set('Referrer-Policy', 'strict-origin-when-cross-origin');
    headers.set('Permissions-Policy', 'geolocation=(), microphone=()');

    return new Response(response.body, {
      status: response.status,
      headers
    });
  }
};
```

#### WAF 규칙

```bash
# Cloudflare WAF 매니지드 룰 활성화
# - OWASP Core Ruleset
# - Cloudflare Managed Ruleset
# - 레이트 리미팅 규칙
# - 봇 보호
```

#### 검증 단계

- [ ] WAF가 OWASP 규칙으로 활성화됨
- [ ] 레이트 리미팅 구성
- [ ] 봇 보호 활성
- [ ] DDoS 보호 활성화
- [ ] 보안 헤더 구성됨
- [ ] SSL/TLS strict 모드 활성화

### 7. 백업과 재해 복구

#### 자동 백업

```terraform
# PASS: 올바름: 자동 RDS 백업
resource "aws_db_instance" "main" {
  allocated_storage     = 20
  engine               = "postgres"

  backup_retention_period = 30  # 30일 보존
  backup_window          = "03:00-04:00"
  maintenance_window     = "mon:04:00-mon:05:00"

  enabled_cloudwatch_logs_exports = ["postgresql"]

  deletion_protection = true  # 실수 삭제 방지
}
```

#### 검증 단계

- [ ] 일별 자동 백업 구성
- [ ] 백업 보존 기간이 컴플라이언스 요구를 충족
- [ ] PITR(Point-in-time recovery) 활성화
- [ ] 분기별 백업 테스트 수행
- [ ] 재해 복구 계획 문서화
- [ ] RPO와 RTO 정의·검증

## 배포 전 클라우드 보안 체크리스트

운영 클라우드 배포 전에 반드시 확인한다:

- [ ] **IAM**: 루트 계정 미사용, MFA 활성화, 최소 권한 정책
- [ ] **시크릿**: 모든 시크릿이 회전과 함께 클라우드 시크릿 매니저에 저장
- [ ] **네트워크**: 보안 그룹 제한, 공개 데이터베이스 없음
- [ ] **로깅**: CloudWatch/로깅이 보존 정책과 함께 활성화
- [ ] **모니터링**: 이상 징후 알림 구성
- [ ] **CI/CD**: OIDC 인증, 시크릿 스캐닝, 의존성 감사
- [ ] **CDN/WAF**: Cloudflare WAF가 OWASP 규칙으로 활성화
- [ ] **암호화**: 데이터가 저장·전송 시 암호화
- [ ] **백업**: 자동 백업과 검증된 복구
- [ ] **컴플라이언스**: 해당 시 GDPR/HIPAA 요구사항 충족
- [ ] **문서화**: 인프라 문서·런북 작성
- [ ] **사고 대응**: 보안 사고 대응 계획 마련

## 자주 발생하는 클라우드 보안 오구성

### S3 버킷 노출

```bash
# FAIL: 잘못됨: 공개 버킷
aws s3api put-bucket-acl --bucket my-bucket --acl public-read

# PASS: 올바름: 특정 접근만 허용된 비공개 버킷
aws s3api put-bucket-acl --bucket my-bucket --acl private
aws s3api put-bucket-policy --bucket my-bucket --policy file://policy.json
```

### RDS 공개 접근

```terraform
# FAIL: 잘못됨
resource "aws_db_instance" "bad" {
  publicly_accessible = true  # 절대 하지 말 것!
}

# PASS: 올바름
resource "aws_db_instance" "good" {
  publicly_accessible = false
  vpc_security_group_ids = [aws_security_group.db.id]
}
```

## 참고자료

- [AWS Security Best Practices](https://aws.amazon.com/security/best-practices/)
- [CIS AWS Foundations Benchmark](https://www.cisecurity.org/benchmark/amazon_web_services)
- [Cloudflare Security Documentation](https://developers.cloudflare.com/security/)
- [OWASP Cloud Security](https://owasp.org/www-project-cloud-security/)
- [Terraform Security Best Practices](https://www.terraform.io/docs/cloud/guides/recommended-practices/)

**기억할 것**: 클라우드 오구성은 데이터 유출의 가장 큰 원인이다. 단 하나의 노출된 S3 버킷이나 지나치게 허용적인 IAM 정책이 전체 인프라를 손상시킬 수 있다. 항상 최소 권한 원칙과 다층 방어(defense in depth)를 따른다.
