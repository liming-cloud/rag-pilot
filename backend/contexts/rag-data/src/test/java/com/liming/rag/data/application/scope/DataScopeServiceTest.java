package com.liming.rag.data.application.scope;

import static org.assertj.core.api.Assertions.assertThat;

import com.liming.rag.data.api.ScopeDecision;
import com.liming.rag.data.application.Fakes;
import com.liming.rag.data.application.Grants;
import com.liming.rag.data.application.GranteeType;
import com.liming.rag.data.application.ScopeUnit;
import com.liming.rag.shared.UserId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DataScopeServiceTest {

    private static final String TOOL = "get_inventory";
    private static final UserId USER = new UserId(1);

    private final Fakes.MemoryGrants grants = new Fakes.MemoryGrants();
    private final Fakes.Directory users = new Fakes.Directory();
    private final Fakes.Master master = new Fakes.Master();
    private DataScopeService service;

    @BeforeEach
    void setUp() {
        master.units = List.of(new ScopeUnit("S1", "一店", "east"), new ScopeUnit("S2", "二店", "east"),
                new ScopeUnit("S3", "三店", "west"));
        service = new DataScopeService(grants, users, master.master());
    }

    private ScopeDecision decide(Set<String> units, Set<String> regions) {
        return service.decide(Fakes.TENANT, USER, TOOL, units, regions);
    }

    private void grantUser(Set<String> tools, Set<String> units, Set<String> regions) {
        grants.replace(Fakes.TENANT, GranteeType.USER, USER.value(), new Grants(tools, units, regions));
    }

    @Test
    @DisplayName("没有任何授权：拒绝，没有放行任何站点")
    void noGrantIsDenied() {
        var decision = decide(Set.of(), Set.of());

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.units()).isEmpty();
    }

    @Test
    @DisplayName("授权了工具但没有数据范围（空范围）：拒绝")
    void emptyScopeIsDenied() {
        grantUser(Set.of(TOOL), Set.of(), Set.of());

        var decision = decide(Set.of("S1"), Set.of());

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.units()).isEmpty();
    }

    @Test
    @DisplayName("授权了站点但没有授权这个工具：拒绝")
    void toolNotGrantedIsDenied() {
        grantUser(Set.of("other_tool"), Set.of("S1"), Set.of());

        assertThat(decide(Set.of("S1"), Set.of()).allowed()).isFalse();
    }

    @Test
    @DisplayName("有授权：不指定站点得到被授权的全部站点；指定站点得到指定的站点")
    void grantedScopeIsReturned() {
        grantUser(Set.of(TOOL), Set.of("S1", "S3"), Set.of());

        assertThat(decide(Set.of(), Set.of()).units()).containsExactlyInAnyOrder("S1", "S3");
        assertThat(decide(Set.of("S3"), Set.of()).units()).containsExactly("S3");
    }

    @Test
    @DisplayName("指定的站点有一个在范围外：整体拒绝，不悄悄缩小范围")
    void partlyOutOfScopeIsDeniedAsAWhole() {
        grantUser(Set.of(TOOL), Set.of("S1"), Set.of());

        var decision = decide(Set.of("S1", "S2"), Set.of());

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.units()).isEmpty();
    }

    @Test
    @DisplayName("区域授权按站点主数据展开：新增站点自动在范围内")
    void regionGrantExpandsWithMasterData() {
        grantUser(Set.of(TOOL), Set.of(), Set.of("east"));
        assertThat(decide(Set.of(), Set.of()).units()).containsExactlyInAnyOrder("S1", "S2");

        master.units = List.of(new ScopeUnit("S1", "一店", "east"), new ScopeUnit("S2", "二店", "east"),
                new ScopeUnit("S4", "四店", "east"));
        assertThat(decide(Set.of("S4"), Set.of()).allowed()).isTrue();
        assertThat(decide(Set.of(), Set.of("east")).units()).containsExactlyInAnyOrder("S1", "S2", "S4");
        assertThat(decide(Set.of(), Set.of("west")).allowed()).isFalse();
    }

    @Test
    @DisplayName("指定了区域但主数据里没有这个区域：拒绝，不当作没有限定")
    void unknownRequestedRegionIsDenied() {
        grantUser(Set.of(TOOL), Set.of("S1"), Set.of());

        assertThat(decide(Set.of(), Set.of("nowhere")).allowed()).isFalse();
    }

    @Test
    @DisplayName("站点主数据取不到：区域授权展开不出站点，只剩直接授权的站点；只有区域授权时拒绝")
    void masterDownFailsClosed() {
        master.down = true;
        grantUser(Set.of(TOOL), Set.of("S1"), Set.of("east"));
        assertThat(decide(Set.of(), Set.of()).units()).containsExactly("S1");
        assertThat(decide(Set.of("S2"), Set.of()).allowed()).isFalse();

        grantUser(Set.of(TOOL), Set.of(), Set.of("east"));
        assertThat(decide(Set.of(), Set.of()).allowed()).isFalse();
    }

    @Test
    @DisplayName("用户授权与角色授权取并集；收回其中一处只影响自己那一条")
    void userAndRoleGrantsAreUnioned() {
        users.roles.put(USER.value(), List.of(100L));
        grantUser(Set.of(TOOL), Set.of("S1"), Set.of());
        grants.replace(Fakes.TENANT, GranteeType.ROLE, 100, new Grants(Set.of(TOOL), Set.of("S2"), Set.of()));
        assertThat(decide(Set.of(), Set.of()).units()).containsExactlyInAnyOrder("S1", "S2");

        grants.replace(Fakes.TENANT, GranteeType.ROLE, 100, Grants.none());
        assertThat(decide(Set.of(), Set.of()).units()).containsExactly("S1");
        assertThat(decide(Set.of("S2"), Set.of()).allowed()).isFalse();

        grantUser(Set.of(), Set.of(), Set.of());
        assertThat(decide(Set.of(), Set.of()).allowed()).isFalse();
    }

    @Test
    @DisplayName("超级管理员同样不绕过：没有授权就拒绝")
    void superAdminDoesNotBypass() {
        users.superAdmins.add(USER.value());

        var decision = decide(Set.of("S1"), Set.of());

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.units()).isEmpty();
    }
}
