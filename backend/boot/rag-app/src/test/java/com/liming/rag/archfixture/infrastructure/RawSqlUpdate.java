package com.liming.rag.archfixture.infrastructure;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;

/** 违规样例：在更新条件里拼原始 SQL（版本号应在 Java 里算好再 set）。 */
public class RawSqlUpdate {

    UpdateWrapper<Object> bumpVersion() {
        return new UpdateWrapper<>().setSql("version = version + 1");
    }
}
