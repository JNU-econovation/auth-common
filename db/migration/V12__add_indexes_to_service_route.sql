-- owner_id 조회 인덱스 — 소유자별 라우트 조회 (클라이언트 상세 조회 API 대비 / 네임스페이스 선점 관련)
CREATE INDEX idx_service_route_owner_id
	ON service_route (owner_id);

-- path_prefix prefix 탐색 인덱스 — 네임스페이스 선점 조회 (LIKE 'prefix%')
CREATE INDEX idx_service_route_path_prefix_text
	ON service_route (path_prefix text_pattern_ops);
