package com.liming.rag.archfixture.web;

import cn.dev33.satoken.stp.StpUtil;

/** 违规样例：在代码里手写角色校验，而不是用 Sa-Token 注解。 */
public class ChecksRoleByHand {

    void delete() {
        StpUtil.checkRole("ADMIN");
    }
}
