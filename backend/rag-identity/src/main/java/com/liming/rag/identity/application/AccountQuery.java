package com.liming.rag.identity.application;

import java.time.OffsetDateTime;

/**
 * 账号列表的筛选（D-039）。
 *
 * @param keyword 匹配用户名或显示名，为空不筛
 * @param roleId 属于这个角色，为 null 不筛
 * @param status 启用、停用或锁定，为 null 不筛
 * @param now 判断“锁定”用的当前时间
 */
public record AccountQuery(String keyword, Long roleId, Status status, OffsetDateTime now, Paged.Request page) {

    public enum Status {
        ENABLED,
        DISABLED,
        /** 连续输错被锁、还没到期。 */
        LOCKED
    }
}
