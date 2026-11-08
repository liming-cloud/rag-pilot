package com.liming.rag.answering.application.ask;

import com.liming.rag.data.api.DataCatalog;
import com.liming.rag.data.api.DataQueries;
import com.liming.rag.data.api.DataResult;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 测试替身：数据查询。记下每次查询的工具和参数，结果由测试设置。 */
final class TestData implements DataQueries {

    DataCatalog catalog = DataCatalog.empty();
    DataResult result = DataResult.failed("没有设置结果");
    final List<String> queries = new ArrayList<>();

    /** 没有可用的数据工具；选工具的模型不应被调用。 */
    static DataLookup none() {
        return new DataLookup(new TestData(), (system, user) -> {
            throw new AssertionError("没有可用工具时不应调用选工具的模型");
        });
    }

    @Override
    public DataCatalog catalog(TenantId tenantId, UserId userId) {
        return catalog;
    }

    @Override
    public DataResult query(TenantId tenantId, UserId userId, String tool, Map<String, Object> arguments) {
        queries.add(tool + " " + arguments);
        return result;
    }
}
