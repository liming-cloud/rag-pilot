package com.liming.rag.data.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.liming.rag.data.api.DataPermissions;
import com.liming.rag.data.application.Grants;
import com.liming.rag.data.application.ScopeUnit;
import com.liming.rag.data.application.grant.DataGrantService;
import com.liming.rag.identity.api.CurrentUser;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 工具与数据范围授权接口（D-056）。都要登录，租户从登录会话取，每个操作对应一个权限点。 */
@RestController
@RequestMapping("/api/data-grants")
@SaCheckLogin
class DataGrantController {

    record ToolView(String name, String title) {
    }

    record UnitView(String id, String name, String region) {
    }

    /** 授权内容，列表按字典序排，便于对比。 */
    record GrantView(List<String> tools, List<String> units, List<String> regions) {
        static GrantView of(Grants grants) {
            return new GrantView(sorted(grants.tools()), sorted(grants.units()), sorted(grants.regions()));
        }

        private static List<String> sorted(Collection<String> values) {
            return List.copyOf(new TreeSet<>(values));
        }
    }

    record GrantRequest(List<String> tools, List<String> units, List<String> regions) {
    }

    private final DataGrantService grants;
    private final CurrentUser currentUser;

    DataGrantController(DataGrantService grants, CurrentUser currentUser) {
        this.grants = grants;
        this.currentUser = currentUser;
    }

    /** 可授权的工具清单（部署配置）。 */
    @SaCheckPermission(DataPermissions.DATA_VIEW)
    @GetMapping("/tools")
    List<ToolView> tools() {
        return grants.tools().stream().map(t -> new ToolView(t.name(), t.title()))
                .toList();
    }

    /** 站点及所属区域（来自客户系统）；取不到时返回 503，页面提示稍后再试。 */
    @SaCheckPermission(DataPermissions.DATA_VIEW)
    @GetMapping("/units")
    List<UnitView> units() {
        return grants.units(currentUser.tenantId()).stream().map(DataGrantController::view).toList();
    }

    /** 授权给一个用户或角色的内容，{@code granteeType} 为 USER 或 ROLE。 */
    @SaCheckPermission(DataPermissions.DATA_VIEW)
    @GetMapping
    GrantView find(@RequestParam String granteeType, @RequestParam long granteeId) {
        return GrantView.of(grants.find(currentUser.tenantId(), granteeType, granteeId));
    }

    /** 整体替换授权：请求里没有的就是收回。 */
    @SaCheckPermission(DataPermissions.DATA_GRANT)
    @PutMapping
    GrantView replace(@RequestParam String granteeType, @RequestParam long granteeId, @RequestBody GrantRequest request) {
        return GrantView.of(grants.replace(currentUser.tenantId(), currentUser.userId(), granteeType, granteeId,
                orEmpty(request.tools()), orEmpty(request.units()), orEmpty(request.regions())));
    }

    private static UnitView view(ScopeUnit unit) {
        return new UnitView(unit.id(), unit.name(), unit.region());
    }

    private static List<String> orEmpty(List<String> values) {
        return values == null ? List.of() : values;
    }
}
