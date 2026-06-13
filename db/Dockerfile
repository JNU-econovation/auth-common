# 마이그레이션 전용 이미지 — 공식 flyway 이미지에 SQL 을 담아 Docker Hub 로 푸시한다.
# 앱(auth-api, api-gateway)과 동일하게 "이미지 pull" 로 배포되므로, 서버는 레포 없이
# docker compose pull 만으로 최신 마이그레이션을 받는다.
# 실행은 docker-compose 의 flyway 서비스가 담당하며, auth-api 는 마이그레이션 완료 후 기동한다.
FROM flyway/flyway:10
COPY migration /flyway/sql
