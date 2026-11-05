package com.liming.rag.settings.web;

import com.liming.rag.settings.application.system.SystemSettingsService;
import com.liming.rag.settings.application.system.BrandingInfo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录页和顶栏显示的产品名称和提示（D-042、D-045）。登录前就要显示，所以不要求登录，和登录接口一样放在
 * {@code /api/auth} 下；只有这三项，不带其他设置。
 */
@RestController
@RequestMapping("/api/auth")
class BrandingController {

    private final SystemSettingsService settings;

    BrandingController(SystemSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping("/branding")
    BrandingInfo branding() {
        return settings.branding();
    }
}
