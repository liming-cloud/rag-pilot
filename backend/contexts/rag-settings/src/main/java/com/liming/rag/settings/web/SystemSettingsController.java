package com.liming.rag.settings.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.liming.rag.identity.api.CurrentUser;
import com.liming.rag.identity.api.IdentityPermissions;
import com.liming.rag.settings.api.IndustryPack;
import com.liming.rag.settings.api.SettingsPermissions;
import com.liming.rag.settings.application.SystemSettingsService;
import com.liming.rag.settings.application.SystemSettingsService.ChatTexts;
import com.liming.rag.settings.application.SystemSettingsService.Item;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统设置接口（D-045）：查看要 setting:view，保存和恢复默认要 setting:update；问答页的文案和推荐问题给能用问答的人。
 * 登录页的品牌见 {@link BrandingController}。
 */
@RestController
@SaCheckLogin
class SystemSettingsController {

    /** 文字项的 value 是字符串，推荐问题是字符串数组。 */
    record SaveRequest(Object value) {
    }

    record ItemView(String key, String kind, Object value, Object defaultValue, String source, String defaultSource,
            OffsetDateTime updatedAt, String updatedBy) {
        static ItemView of(Item item) {
            boolean list = item.key().list();
            return new ItemView(item.key().name(), list ? "list" : "text", shape(list, item.value()),
                    shape(list, item.defaultValue()), item.source().name(), item.defaultSource().name(),
                    item.updatedAt(), item.updatedBy());
        }

        private static Object shape(boolean list, List<String> value) {
            return list ? value : value.getFirst();
        }
    }

    record SettingsView(IndustryPack industryPack, String statsZone, List<ItemView> items) {
    }

    private final SystemSettingsService settings;
    private final CurrentUser currentUser;

    SystemSettingsController(SystemSettingsService settings, CurrentUser currentUser) {
        this.settings = settings;
        this.currentUser = currentUser;
    }

    @SaCheckPermission(SettingsPermissions.SETTING_VIEW)
    @GetMapping("/api/settings")
    SettingsView overview() {
        var overview = settings.overview();
        return new SettingsView(overview.pack(), overview.statsZone(),
                overview.items().stream().map(ItemView::of).toList());
    }

    /** 问答页的输入框占位、空状态说明和推荐问题。 */
    @SaCheckPermission(IdentityPermissions.CHAT_USE)
    @GetMapping("/api/settings/chat")
    ChatTexts chat() {
        return settings.chatTexts();
    }

    @SaCheckPermission(SettingsPermissions.SETTING_UPDATE)
    @PutMapping("/api/settings/{key}")
    ItemView save(@PathVariable String key, @RequestBody SaveRequest request) {
        return ItemView.of(settings.save(currentUser.tenantId(), currentUser.userId(), key,
                request == null ? null : request.value()));
    }

    @SaCheckPermission(SettingsPermissions.SETTING_UPDATE)
    @DeleteMapping("/api/settings/{key}")
    ItemView restore(@PathVariable String key) {
        return ItemView.of(settings.restore(currentUser.tenantId(), currentUser.userId(), key));
    }
}
