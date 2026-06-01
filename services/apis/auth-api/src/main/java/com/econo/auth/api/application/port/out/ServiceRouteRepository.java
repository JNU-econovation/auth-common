package com.econo.auth.api.application.port.out;

import com.econo.auth.api.domain.ServiceRoute;
import java.util.List;

/** ServiceRoute 저장소 포트 (out) */
public interface ServiceRouteRepository {

	/**
	 * ServiceRoute 저장
	 *
	 * @param serviceRoute 저장할 ServiceRoute
	 */
	void save(ServiceRoute serviceRoute);

	/**
	 * 모든 ServiceRoute 조회
	 *
	 * @return 등록된 라우트 목록
	 */
	List<ServiceRoute> findAll();
}
