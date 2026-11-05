package com.liming.rag.archfixture.web;

import cn.dev33.satoken.annotation.SaCheckPermission;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 违规样例：标的权限点不在任何上下文声明的权限点清单里。 */
@RestController
public class UnknownPermission {

    @SaCheckPermission("kb:fly")
    @PostMapping("/api/fixture/knowledge-bases")
    String create() {
        return "";
    }
}
