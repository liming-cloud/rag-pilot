package com.liming.rag;

import com.liming.rag.answering.api.AnsweringPermissions;
import com.liming.rag.identity.api.IdentityPermissions;
import com.liming.rag.identity.api.PermissionCatalog.Section;
import com.liming.rag.knowledge.api.KnowledgePermissions;
import com.liming.rag.settings.api.SettingsPermissions;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** 测试用：各上下文声明的全部权限点（#136），按菜单位置排列，口径与 identity 的 {@code PermissionRegistry} 一致。 */
final class AllPermissions {

    static final List<Section> SECTIONS = Stream.of(AnsweringPermissions.SECTIONS, KnowledgePermissions.SECTIONS,
                    IdentityPermissions.SECTIONS, SettingsPermissions.SECTIONS)
            .flatMap(List::stream).sorted(Comparator.comparingInt(Section::position)).toList();

    static final Set<String> ALL = all();

    private AllPermissions() {
    }

    private static Set<String> all() {
        var codes = new LinkedHashSet<String>();
        SECTIONS.forEach(section -> codes.addAll(section.group().codes()));
        return codes;
    }
}
