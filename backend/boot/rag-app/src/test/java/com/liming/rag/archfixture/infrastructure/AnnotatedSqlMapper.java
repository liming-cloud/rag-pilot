package com.liming.rag.archfixture.infrastructure;

import org.apache.ibatis.annotations.Select;

/** 违规样例：SQL 写在注解里，而不是 Mapper XML。 */
public interface AnnotatedSqlMapper {

    @Select("SELECT 1")
    int one();
}
