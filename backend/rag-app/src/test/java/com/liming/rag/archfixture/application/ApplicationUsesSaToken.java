package com.liming.rag.archfixture.application;

import cn.dev33.satoken.stp.StpUtil;

/** 违规样例：应用层直接调用 Sa-Token 踢下线，应该经 identity 的 Sessions 端口。 */
public class ApplicationUsesSaToken {

    void disable(long accountId) {
        StpUtil.logout(accountId);
    }
}
