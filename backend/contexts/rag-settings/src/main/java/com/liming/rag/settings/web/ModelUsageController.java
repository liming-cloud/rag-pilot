package com.liming.rag.settings.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.liming.rag.identity.api.CurrentUser;
import com.liming.rag.settings.api.SettingsPermissions;
import com.liming.rag.settings.application.model.ModelUsageService;
import com.liming.rag.settings.application.model.PriceView;
import com.liming.rag.settings.application.model.UsageGroup;
import com.liming.rag.settings.application.model.UsageReport;
import com.liming.rag.shared.Paged;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 模型用量与费用接口（#169）：查看要 model:view，改单价要 model:update。 */
@RestController
@SaCheckLogin
class ModelUsageController {

    record PriceRequest(String model, @Nullable BigDecimal inputPerMillion, @Nullable BigDecimal outputPerMillion) {
    }

    /** 分页响应（D-039）：{@code groups} 是 {@code {items, total, page, size}}。 */
    record UsageResponse(LocalDate from, LocalDate to, String groupBy, Paged<UsageGroup> groups, UsageGroup total,
            List<PriceView> prices) {
        static UsageResponse of(UsageReport report, List<PriceView> prices) {
            return new UsageResponse(report.from(), report.to(), report.groupBy().name(), report.groups(),
                    report.total(), prices);
        }
    }

    private final ModelUsageService usage;
    private final CurrentUser currentUser;

    ModelUsageController(ModelUsageService usage, CurrentUser currentUser) {
        this.usage = usage;
        this.currentUser = currentUser;
    }

    /** 日期写 yyyy-MM-dd，不传看最近 30 天；groupBy 取 DETAIL、DAY、MONTH、PURPOSE、MODEL，默认 DETAIL；分页。 */
    @SaCheckPermission(SettingsPermissions.MODEL_VIEW)
    @GetMapping("/api/models/usage")
    UsageResponse usage(@RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "groupBy", required = false) String groupBy,
            Paged.Request page) {
        return UsageResponse.of(usage.usage(from, to, groupBy, page), usage.prices());
    }

    @SaCheckPermission(SettingsPermissions.MODEL_UPDATE)
    @PutMapping("/api/models/prices")
    PriceView savePrice(@RequestBody PriceRequest request) {
        return usage.savePrice(currentUser.tenantId(), currentUser.userId(), request.model(),
                request.inputPerMillion(), request.outputPerMillion());
    }
}
