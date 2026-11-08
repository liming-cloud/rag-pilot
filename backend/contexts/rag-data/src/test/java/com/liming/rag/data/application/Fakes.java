package com.liming.rag.data.application;

import com.liming.rag.identity.api.AuditTrail;
import com.liming.rag.identity.api.UserDirectory;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** 测试替身：内存里的授权存取、账号目录、站点主数据和审计。 */
public final class Fakes {

    public static final TenantId TENANT = new TenantId(1);

    private Fakes() {
    }

    public static final class MemoryGrants implements DataGrantStore {
        private final Map<String, Grants> byGrantee = new HashMap<>();

        private static String key(GranteeType type, long id) {
            return type + ":" + id;
        }

        @Override
        public Grants find(TenantId tenantId, GranteeType type, long granteeId) {
            return byGrantee.getOrDefault(key(type, granteeId), Grants.none());
        }

        @Override
        public Grants findEffective(TenantId tenantId, UserId userId, Collection<Long> roleIds) {
            var tools = new HashSet<String>();
            var units = new HashSet<String>();
            var regions = new HashSet<String>();
            var all = new ArrayList<Grants>();
            all.add(find(tenantId, GranteeType.USER, userId.value()));
            roleIds.forEach(id -> all.add(find(tenantId, GranteeType.ROLE, id)));
            all.forEach(g -> {
                tools.addAll(g.tools());
                units.addAll(g.units());
                regions.addAll(g.regions());
            });
            return new Grants(tools, units, regions);
        }

        @Override
        public void replace(TenantId tenantId, GranteeType type, long granteeId, Grants grants) {
            byGrantee.put(key(type, granteeId), grants);
        }

        @Override
        public void removeRole(TenantId tenantId, long roleId) {
            byGrantee.remove(key(GranteeType.ROLE, roleId));
        }
    }

    /** 账号 1～9 存在，角色 100～109 存在；账号到角色的关系由测试设置；超级管理员只用来证明判定不看它。 */
    public static final class Directory implements UserDirectory {
        public final Map<Long, List<Long>> roles = new HashMap<>();
        public final Set<Long> superAdmins = new HashSet<>();

        @Override
        public boolean exists(TenantId tenantId, UserId userId) {
            return userId.value() >= 1 && userId.value() <= 9;
        }

        @Override
        public List<Long> roleIds(TenantId tenantId, UserId userId) {
            return roles.getOrDefault(userId.value(), List.of());
        }

        @Override
        public boolean isSuperAdmin(TenantId tenantId, UserId userId) {
            return superAdmins.contains(userId.value());
        }

        @Override
        public boolean roleExists(TenantId tenantId, long roleId) {
            return roleId >= 100 && roleId <= 109;
        }

        @Override
        public int countWithPermission(TenantId tenantId, String permission) {
            return 0;
        }

        @Override
        public Map<Long, UserSummary> summaries(TenantId tenantId, Collection<Long> userIds) {
            return Map.of();
        }
    }

    /** 站点主数据：可以设为取不到。 */
    public static final class Master implements UnitDirectory {
        public List<ScopeUnit> units = List.of();
        public boolean down;

        @Override
        public List<ScopeUnit> units(TenantId tenantId) {
            if (down) {
                throw new DataException(DataException.Reason.UNAVAILABLE, "站点信息暂时取不到，请稍后再试");
            }
            return units;
        }

        public UnitMaster master() {
            return new UnitMaster(this, new UnitCache() {
                @Override
                public List<ScopeUnit> get(TenantId tenantId, Supplier<List<ScopeUnit>> loader) {
                    return loader.get();
                }
            });
        }
    }

    public static final class Audit implements AuditTrail {
        public final List<String> entries = new ArrayList<>();

        @Override
        public void record(TenantId tenantId, UserId actor, String action, String targetType, String targetId,
                String detail) {
            entries.add(action + "|" + targetType + "|" + targetId + "|" + detail);
        }
    }
}
