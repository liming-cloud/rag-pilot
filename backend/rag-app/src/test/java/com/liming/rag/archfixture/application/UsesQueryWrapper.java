package com.liming.rag.archfixture.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

/** 违规样例：application 包里直接用 MyBatis-Plus 的 Wrapper。 */
public class UsesQueryWrapper {

    QueryWrapper<Object> query() {
        return new QueryWrapper<>();
    }
}
