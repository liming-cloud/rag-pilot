package com.liming.rag.data.api;

import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.util.Map;

/**
 * 按提问人的数据范围查询客户系统的只读工具（D-058 的三道检查都在这里）：调用前判定范围，令牌带上范围，
 * 返回后逐行校验，范围外的行丢弃；每次调用（含被拒绝、没发出的）记一条审计。模型只负责选工具、填参数。
 */
public interface DataQueries {

    /** 提问人能用的工具和能看的站点，给模型选工具、填参数用；没有任何工具授权时为空，不发请求。 */
    DataCatalog catalog(TenantId tenantId, UserId userId);

    /**
     * @param arguments 模型填的参数，只放结构化条件；其中的站点（{@code store_id}、{@code store_ids}）和区域
     *                  （{@code region}、{@code regions}）先经范围判定
     */
    DataResult query(TenantId tenantId, UserId userId, String tool, Map<String, Object> arguments);
}
