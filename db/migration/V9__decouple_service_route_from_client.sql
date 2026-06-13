-- service_route를 service_client에서 분리: FK 제약 제거 + nullable 전환
-- 라우트를 클라이언트 독립적으로 CRUD할 수 있도록 정합 유지

ALTER TABLE service_route
    DROP CONSTRAINT fk_service_route_client;

ALTER TABLE service_route
    ALTER COLUMN registered_client_id DROP NOT NULL;

COMMENT ON COLUMN service_route.registered_client_id
    IS '연결된 클라이언트 registeredClientId (nullable — 라우트와 클라이언트 분리로 FK 제거됨. 기존 연관 데이터 호환용 컬럼 보존)';
