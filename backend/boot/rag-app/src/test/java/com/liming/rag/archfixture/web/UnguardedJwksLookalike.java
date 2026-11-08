package com.liming.rag.archfixture.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 违规样例：路径只是和公钥 JWKS 接口相似，不在匿名白名单里，仍须标权限点。 */
@RestController
public class UnguardedJwksLookalike {

    @GetMapping("/api/integration/jwks.json/more")
    String list() {
        return "";
    }
}
