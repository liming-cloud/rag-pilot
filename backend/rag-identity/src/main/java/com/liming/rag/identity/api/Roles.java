package com.liming.rag.identity.api;

/** 角色名，供 {@code @SaCheckRole} 使用。 */
public final class Roles {

    /** 管理知识库、文档和账号。 */
    public static final String ADMIN = "ADMIN";
    /** 门店员工，只能问答。 */
    public static final String STAFF = "STAFF";

    private Roles() {
    }
}
