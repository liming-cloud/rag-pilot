package com.liming.rag.identity.application.account;

import com.liming.rag.identity.application.Paged;

import java.time.OffsetDateTime;
import org.jspecify.annotations.Nullable;

/**
 * 账号列表的筛选（D-039）。
 *
 * @param keyword 匹配用户名或显示名，为空不筛
 * @param roleId 属于这个角色，为 null 不筛
 * @param status 启用、停用或锁定，为 null 不筛
 * @param now 判断“锁定”用的当前时间
 */
public record AccountQuery(@Nullable String keyword, @Nullable Long roleId, @Nullable Status status, OffsetDateTime now,
        Paged.Request page) {

    public enum Status {
        ENABLED,
        DISABLED,
        /** 连续输错被锁、还没到期。 */
        LOCKED
    }
}
