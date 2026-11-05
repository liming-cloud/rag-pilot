package com.liming.rag.knowledge.domain;

import com.liming.rag.shared.AggregateRoot;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * 知识库聚合（D-038、#136）：一组文档和它的授权——直接授权给哪些账号、授权给哪些角色。
 *
 * <p>聚合管授权的增减规则：建库的人自动获得直接授权；不能撤销自己（撤销后这个库可能从自己的管理界面上消失，也无法再授权
 * 回来）；重复授权、撤销没有的授权都不算变化。聚合记下这次加了谁、去了谁，仓储只写这些变化，两个人同时授权不会互相覆盖。
 *
 * <p>“谁能访问这个库”不在这里判断：检索和管理都按 {@code ChunkSearchMapper.accessibleKnowledgeBases} 那一个 SQL 片段
 * 算（直接授权 ∪ 所属角色被授权），口径只写一处。被授权的账号、角色是否存在由应用层向 identity 查。
 */
public final class KnowledgeBase implements AggregateRoot {

    /** 名称长度上限，与数据库列宽一致。 */
    public static final int MAX_NAME_LENGTH = 100;

    private final Long id;
    private final TenantId tenantId;
    private final String name;
    private final Set<UserId> members;
    private final Set<Long> roleGrants;
    private final Set<UserId> addedMembers = new LinkedHashSet<>();
    private final Set<UserId> removedMembers = new LinkedHashSet<>();
    private final Set<Long> addedRoles = new LinkedHashSet<>();
    private final Set<Long> removedRoles = new LinkedHashSet<>();

    private KnowledgeBase(@Nullable Long id, TenantId tenantId, String name, Collection<UserId> members,
            Collection<Long> roleGrants) {
        this.id = id;
        this.tenantId = Objects.requireNonNull(tenantId, "租户不能为空");
        this.name = name;
        this.members = new LinkedHashSet<>(members);
        this.roleGrants = new LinkedHashSet<>(roleGrants);
    }

    /**
     * 新建知识库，建库的人自动获得直接授权。
     *
     * @throws IllegalArgumentException 当名称为空或超过 {@value #MAX_NAME_LENGTH} 个字符时
     */
    public static KnowledgeBase create(TenantId tenantId, String name, UserId creator) {
        var trimmed = name == null ? "" : name.strip();
        if (trimmed.isEmpty() || trimmed.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("知识库名称必须是 1 到 " + MAX_NAME_LENGTH + " 个字符");
        }
        var created = new KnowledgeBase(null, tenantId, trimmed, Set.of(), Set.of());
        created.grant(Objects.requireNonNull(creator, "建库人不能为空"));
        return created;
    }

    /**
     * 仓储从数据库恢复聚合时使用，不做业务校验。
     *
     * @param members 直接授权的账号，按授权先后
     * @param roleGrants 授权的角色，按授权先后
     */
    public static KnowledgeBase restore(long id, TenantId tenantId, String name, Collection<UserId> members,
            Collection<Long> roleGrants) {
        return new KnowledgeBase(id, tenantId, name, members, roleGrants);
    }

    /** 新建后由仓储回填标识，这次的授权变化一并带上。 */
    public KnowledgeBase withId(long newId) {
        var copy = new KnowledgeBase(newId, tenantId, name, members, roleGrants);
        copy.addedMembers.addAll(addedMembers);
        copy.removedMembers.addAll(removedMembers);
        copy.addedRoles.addAll(addedRoles);
        copy.removedRoles.addAll(removedRoles);
        return copy;
    }

    /** 直接授权给账号；已经授权时不算变化。 */
    public void grant(UserId user) {
        Objects.requireNonNull(user, "账号不能为空");
        if (members.add(user) && !removedMembers.remove(user)) {
            addedMembers.add(user);
        }
    }

    /**
     * 撤销账号的直接授权；没有授权时不算变化。
     *
     * @throws IllegalArgumentException 撤销的是自己
     */
    public void revoke(UserId actor, UserId user) {
        if (Objects.equals(actor, user)) {
            throw new IllegalArgumentException("不能撤销自己的权限");
        }
        if (members.remove(user) && !addedMembers.remove(user)) {
            removedMembers.add(user);
        }
    }

    /** 授权给角色：属于这个角色的账号都能访问；已经授权时不算变化。 */
    public void grantRole(long roleId) {
        if (roleGrants.add(roleId) && !removedRoles.remove(roleId)) {
            addedRoles.add(roleId);
        }
    }

    /** 收回角色授权；没有授权时不算变化。 */
    public void revokeRole(long roleId) {
        if (roleGrants.remove(roleId) && !addedRoles.remove(roleId)) {
            removedRoles.add(roleId);
        }
    }

    public Long id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public String name() {
        return name;
    }

    /** 直接授权的账号，按授权先后。 */
    public Set<UserId> members() {
        return Collections.unmodifiableSet(members);
    }

    /** 授权的角色，按授权先后。 */
    public Set<Long> roleGrants() {
        return Collections.unmodifiableSet(roleGrants);
    }

    /** 加载以来新加的直接授权，仓储保存时写入。 */
    public Set<UserId> addedMembers() {
        return Collections.unmodifiableSet(addedMembers);
    }

    /** 加载以来撤销的直接授权，仓储保存时删除。 */
    public Set<UserId> removedMembers() {
        return Collections.unmodifiableSet(removedMembers);
    }

    /** 加载以来新加的角色授权。 */
    public Set<Long> addedRoles() {
        return Collections.unmodifiableSet(addedRoles);
    }

    /** 加载以来收回的角色授权。 */
    public Set<Long> removedRoles() {
        return Collections.unmodifiableSet(removedRoles);
    }
}
