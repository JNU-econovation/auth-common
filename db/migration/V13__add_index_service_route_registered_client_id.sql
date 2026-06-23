-- registered_client_id 조회 인덱스 — 클라이언트별 라우트 단건 조회 및 삭제
-- (findByRegisteredClientId, deleteAllByRegisteredClientId)
-- PostgreSQL에서 CREATE INDEX CONCURRENTLY는 Flyway 트랜잭션 내 실행 불가 → 일반 CREATE INDEX 사용
CREATE INDEX idx_service_route_registered_client_id
    ON service_route (registered_client_id);
