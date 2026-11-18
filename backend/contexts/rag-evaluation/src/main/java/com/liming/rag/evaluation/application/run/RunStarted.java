package com.liming.rag.evaluation.application.run;

import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;

/** 开始了一次运行；事务提交后由 {@link RunWorker} 在后台逐题跑。 */
record RunStarted(TenantId tenantId, UserId runner, long runId) {
}
