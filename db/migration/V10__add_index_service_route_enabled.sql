-- service_route enabled 컬럼 인덱스 추가
-- 게이트웨이 기동·RefreshRoutesEvent 처리 시 findAllByEnabled(true) 풀스캔 방지

CREATE INDEX idx_service_route_enabled
    ON service_route (enabled);
