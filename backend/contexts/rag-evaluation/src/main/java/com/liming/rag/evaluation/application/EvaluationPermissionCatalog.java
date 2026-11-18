package com.liming.rag.evaluation.application;

import com.liming.rag.evaluation.api.EvaluationPermissions;
import com.liming.rag.identity.api.PermissionCatalog;
import java.util.List;
import org.springframework.stereotype.Component;

/** 把 {@link EvaluationPermissions} 交给 identity 汇总（#224）。内置角色默认都不给（D-070）。 */
@Component
class EvaluationPermissionCatalog implements PermissionCatalog {

    @Override
    public List<Section> sections() {
        return EvaluationPermissions.SECTIONS;
    }
}
