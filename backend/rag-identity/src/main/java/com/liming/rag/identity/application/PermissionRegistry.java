package com.liming.rag.identity.application;

import com.liming.rag.identity.api.PermissionCatalog;
import com.liming.rag.identity.api.PermissionCatalog.Group;
import com.liming.rag.identity.api.PermissionCatalog.Section;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 全系统的权限点清单（#136）：启动时收集各上下文声明的 {@link PermissionCatalog}，按菜单位置排好。
 * 权限判定、角色页的权限列表、超级管理员的“全部权限点”和内置角色的默认权限都以它为准。
 * 两个上下文声明了同一个权限点或同一个菜单位置时启动失败，不带着有歧义的清单运行。
 */
@Component
public class PermissionRegistry {

    private final List<Group> groups;
    private final Set<String> all;
    private final Set<String> adminDefaults;
    private final Set<String> staffDefaults;

    public PermissionRegistry(Collection<PermissionCatalog> catalogs) {
        var sections = catalogs.stream().flatMap(catalog -> catalog.sections().stream())
                .sorted(Comparator.comparingInt(Section::position)).toList();
        var positions = new HashSet<Integer>();
        var codes = new LinkedHashSet<String>();
        for (var section : sections) {
            if (!positions.add(section.position())) {
                throw new IllegalStateException("两个菜单用了同一个位置：" + section.position());
            }
            for (var code : section.group().codes()) {
                if (!codes.add(code)) {
                    throw new IllegalStateException("权限点重复声明：" + code);
                }
            }
        }
        this.groups = sections.stream().map(Section::group).toList();
        this.all = Collections.unmodifiableSet(codes);
        this.adminDefaults = defaults(catalogs.stream().flatMap(catalog -> catalog.adminDefaults().stream()).toList());
        this.staffDefaults = defaults(catalogs.stream().flatMap(catalog -> catalog.staffDefaults().stream()).toList());
    }

    /** 角色页上的菜单分组，按位置排列。 */
    public List<Group> groups() {
        return groups;
    }

    /** 全部权限点，按菜单位置和声明顺序。 */
    public Set<String> all() {
        return all;
    }

    /** 这些权限点按清单顺序排列；清单外的（已删掉的旧权限点）不算。 */
    public List<String> ordered(Collection<String> codes) {
        return all.stream().filter(codes::contains).toList();
    }

    Set<String> adminDefaults() {
        return adminDefaults;
    }

    Set<String> staffDefaults() {
        return staffDefaults;
    }

    private Set<String> defaults(List<String> codes) {
        var unknown = codes.stream().filter(code -> !all.contains(code)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalStateException("内置角色的默认权限点不在清单里：" + unknown);
        }
        return Set.copyOf(codes);
    }
}
