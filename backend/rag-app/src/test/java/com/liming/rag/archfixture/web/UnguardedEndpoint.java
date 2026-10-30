package com.liming.rag.archfixture.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 违规样例：管理接口只要求登录，没有标权限点。 */
@RestController
@SaCheckLogin
public class UnguardedEndpoint {

    @GetMapping("/api/fixture/users")
    String list() {
        return "";
    }
}
