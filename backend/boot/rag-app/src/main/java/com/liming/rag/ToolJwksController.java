package com.liming.rag;

import com.liming.rag.ai.ToolJwks;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工具调用令牌的验签公钥（JWKS，D-058 第 3 节、D-060）。客户系统缓存它来校验我方签发的令牌；只含公钥，所以不要求登录，
 * 是唯一的匿名接口（{@code ArchitectureRulesTest} 的白名单只认这个路径）。
 */
@RestController
class ToolJwksController {

    private final ToolJwks jwks;

    ToolJwksController(ToolJwks jwks) {
        this.jwks = jwks;
    }

    @GetMapping("/api/integration/jwks.json")
    Map<String, Object> jwks() {
        return jwks.jwks();
    }
}
