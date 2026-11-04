package com.liming.rag.archfixture.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

/** 违规样例：在条件构造器里拼原始 SQL。 */
public class RawSqlWrapper {

    QueryWrapper<Object> latest() {
        return new QueryWrapper<>().last("LIMIT 1");
    }
}
