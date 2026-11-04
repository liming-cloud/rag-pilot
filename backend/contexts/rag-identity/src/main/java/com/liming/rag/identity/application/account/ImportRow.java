package com.liming.rag.identity.application.account;

import java.util.List;

/** 文件里的一行；line 是文件里的行号（表头是第 1 行），用于报错定位。 */
public record ImportRow(int line, String username, String displayName, List<String> roleNames) {

    public ImportRow {
        username = username == null ? "" : username.strip();
        displayName = displayName == null ? "" : displayName.strip();
        roleNames = roleNames == null ? List.of() : roleNames.stream().map(String::strip)
                .filter(name -> !name.isEmpty()).distinct().toList();
    }
}
