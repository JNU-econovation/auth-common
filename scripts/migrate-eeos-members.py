#!/usr/bin/env python3
"""
EEOS-BE MySQL → auth-api PostgreSQL 회원 데이터 이전 스크립트

대상:
  - member_oath_server_type = 'EEOS' 이고 account 행이 존재하는 회원만 이전
  - Slack/GitHub OAuth 전용 회원은 password가 없으므로 제외
  - is_deleted = 0 인 회원만 이전

이름 파싱:
  - EEOS member_name 형식: "30기 홍길동"
  - auth-api name: "홍길동", generation: 30

실행:
  pip install pymysql psycopg2-binary
  python3 migrate-eeos-members.py [--dry-run]

환경 변수:
  EEOS_MYSQL_HOST    (기본: 127.0.0.1)
  EEOS_MYSQL_PORT    (기본: 13308)
  EEOS_MYSQL_USER    (기본: root)
  EEOS_MYSQL_PASS    (기본: root)
  EEOS_MYSQL_DB      (기본: eeos)
  AUTH_PG_HOST       (기본: 127.0.0.1)
  AUTH_PG_PORT       (기본: 5433)
  AUTH_PG_USER       (기본: auth)
  AUTH_PG_PASS       (기본: auth1234)
  AUTH_PG_DB         (기본: authdb)
"""

import os
import re
import sys
import argparse

try:
    import pymysql
    import psycopg2
    import psycopg2.extras
except ImportError:
    print("필요한 패키지 설치: pip install pymysql psycopg2-binary")
    sys.exit(1)


# ──────────────────────────────────────────
# 설정
# ──────────────────────────────────────────

MYSQL_CONF = {
    "host":     os.getenv("EEOS_MYSQL_HOST", "127.0.0.1"),
    "port":     int(os.getenv("EEOS_MYSQL_PORT", "13308")),
    "user":     os.getenv("EEOS_MYSQL_USER", "root"),
    "password": os.getenv("EEOS_MYSQL_PASS", "root"),
    "database": os.getenv("EEOS_MYSQL_DB",   "eeos"),
    "charset":  "utf8mb4",
}

PG_CONF = {
    "host":     os.getenv("AUTH_PG_HOST", "127.0.0.1"),
    "port":     int(os.getenv("AUTH_PG_PORT", "5433")),
    "user":     os.getenv("AUTH_PG_USER", "auth"),
    "password": os.getenv("AUTH_PG_PASS", "auth1234"),
    "dbname":   os.getenv("AUTH_PG_DB",   "authdb"),
}

# auth-api members.login_id VARCHAR(20)
MAX_LOGIN_ID_LEN = 20
# auth-api members.status CHECK (status IN ('AM','RM','CM','OB'))
VALID_STATUSES = {"AM", "RM", "CM", "OB"}


# ──────────────────────────────────────────
# 파싱 유틸
# ──────────────────────────────────────────

GENERATION_PATTERN = re.compile(r"^(\d+)기\s+(.+)$")


def parse_member_name(raw_name: str):
    """
    "30기 홍길동" → (30, "홍길동")
    파싱 실패 시 → (None, raw_name)
    """
    m = GENERATION_PATTERN.match(raw_name.strip())
    if m:
        return int(m.group(1)), m.group(2).strip()
    return None, raw_name.strip()


# ──────────────────────────────────────────
# 메인
# ──────────────────────────────────────────

def fetch_eeos_members(cursor):
    """이전 대상 회원 조회 — EEOS 타입 + account 존재 + 미삭제"""
    cursor.execute(
        """
        SELECT
            m.member_id,
            m.member_name,
            m.member_active_status,
            m.created_date,
            a.account_login_id,
            a.account_login_passwd
        FROM member m
        INNER JOIN account a ON a.account_member_id = m.member_id
        WHERE m.is_deleted = 0
          AND m.member_oath_server_type = 'EEOS'
        ORDER BY m.member_id
        """
    )
    return cursor.fetchall()


def fetch_existing_login_ids(pg_cursor):
    """auth-api에 이미 존재하는 login_id 목록"""
    pg_cursor.execute("SELECT login_id FROM members")
    return {row[0] for row in pg_cursor.fetchall()}


def migrate(dry_run: bool):
    print(f"{'[DRY RUN] ' if dry_run else ''}EEOS MySQL → auth-api PostgreSQL 회원 이전 시작")
    print(f"  MySQL : {MYSQL_CONF['host']}:{MYSQL_CONF['port']}/{MYSQL_CONF['database']}")
    print(f"  PG    : {PG_CONF['host']}:{PG_CONF['port']}/{PG_CONF['dbname']}")
    print()

    # ── MySQL 연결
    my_conn = pymysql.connect(**MYSQL_CONF)
    my_cur = my_conn.cursor()

    # ── PostgreSQL 연결
    pg_conn = psycopg2.connect(**PG_CONF)
    pg_cur = pg_conn.cursor()

    rows = fetch_eeos_members(my_cur)
    existing = fetch_existing_login_ids(pg_cur)
    print(f"이전 대상 회원(EEOS 타입): {len(rows)}명")
    print(f"auth-api 기존 회원 수    : {len(existing)}명")
    print()

    inserted = skipped = warned = 0

    for row in rows:
        member_id, raw_name, status, created_date, login_id, hashed_password = row

        # ── 이름/기수 파싱
        generation, name = parse_member_name(raw_name)
        if generation is None:
            print(f"  [WARN] member_id={member_id} 이름 파싱 실패: '{raw_name}' — 기수를 1로 설정")
            generation = 1
            warned += 1

        # ── status 검증
        if status not in VALID_STATUSES:
            print(f"  [SKIP] member_id={member_id} login_id={login_id} status='{status}' — auth-api 미지원 값")
            skipped += 1
            continue

        # ── login_id 길이 검증
        if len(login_id) > MAX_LOGIN_ID_LEN:
            print(f"  [SKIP] member_id={member_id} login_id='{login_id}'({len(login_id)}자) — 20자 초과")
            skipped += 1
            continue

        # ── 중복 검사
        if login_id in existing:
            print(f"  [SKIP] login_id='{login_id}' — auth-api에 이미 존재")
            skipped += 1
            continue

        # ── generation 범위 검증 (1~99)
        if not (1 <= generation <= 99):
            print(f"  [WARN] member_id={member_id} generation={generation} — 범위 초과, 조정 안함")
            warned += 1

        if not dry_run:
            pg_cur.execute(
                """
                INSERT INTO members (name, login_id, hashed_password, generation, status, created_at)
                VALUES (%s, %s, %s, %s, %s, %s)
                """,
                (name, login_id, hashed_password, generation, status, created_date),
            )
            existing.add(login_id)

        print(f"  [{'DRY' if dry_run else 'OK '}] member_id={member_id} login_id={login_id} "
              f"name='{name}' generation={generation} status={status}")
        inserted += 1

    if not dry_run:
        pg_conn.commit()
        print()
        print(f"커밋 완료.")

    print()
    print("=" * 50)
    print(f"  삽입: {inserted}명")
    print(f"  스킵: {skipped}명")
    print(f"  경고: {warned}건")
    print("=" * 50)

    my_cur.close()
    my_conn.close()
    pg_cur.close()
    pg_conn.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="EEOS → auth-api 회원 이전")
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="실제 삽입 없이 이전 대상과 변환 결과만 출력",
    )
    args = parser.parse_args()
    migrate(dry_run=args.dry_run)
