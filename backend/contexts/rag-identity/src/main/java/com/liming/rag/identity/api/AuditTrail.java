package com.liming.rag.identity.api;

import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;

/**
 * 管理操作的审计记录（#145 C2）：谁、什么时候、对什么做了什么。只增不改，保留 1 年。
 * 授权、角色、账号、资料、系统设置这些管理操作由各上下文在自己的应用服务里、和操作同一个事务里记一条；
 * 模型变更另有变更记录（D-043），不重复记。
 */
public interface AuditTrail {

    /**
     * @param action     操作，按“对象_动作”写，如 {@code KB_MEMBER_GRANTED}，由调用方的上下文定义
     * @param targetType 对象类型，如 {@code KNOWLEDGE_BASE}、{@code ROLE}、{@code ACCOUNT}、{@code DOCUMENT}、{@code SETTING}
     * @param targetId   对象的标识（编号或设置项的名字）
     * @param detail     给管理员看的中文说明，不含密码、密钥
     */
    void record(TenantId tenantId, UserId actor, String action, String targetType, String targetId, String detail);
}
