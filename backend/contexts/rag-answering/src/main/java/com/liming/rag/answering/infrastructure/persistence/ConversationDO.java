package com.liming.rag.answering.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import org.apache.ibatis.type.ArrayTypeHandler;
import org.jspecify.annotations.NullUnmarked;

/**
 * conversation 的一行：单表写入用 {@link ConversationMapper} 的通用方法；查询另带主线最后一轮的上一轮（连表，写在 XML 里）。
 * 只在 infrastructure 包内读取。
 */
@TableName("conversation")
@NullUnmarked
final class ConversationDO {

    @TableId(type = IdType.AUTO)
    Long id;
    Long tenantId;
    Long userId;
    /** 写入时不传，由数据库默认为 0；更新时按版本号判断有没有被别人改过。 */
    Long version;
    String title;
    String titleSource;
    @TableField(typeHandler = ArrayTypeHandler.class)
    Long[] knowledgeBaseIds;
    OffsetDateTime lastAskedAt;
    Long lastTurnId;
    @TableField(exist = false)
    Long lastTurnParentId;

    Long getId() {
        return id;
    }

    Long getTenantId() {
        return tenantId;
    }

    Long getVersion() {
        return version;
    }

    String getTitle() {
        return title;
    }

    String getTitleSource() {
        return titleSource;
    }

    Long[] getKnowledgeBaseIds() {
        return knowledgeBaseIds;
    }

    OffsetDateTime getLastAskedAt() {
        return lastAskedAt;
    }

    Long getLastTurnId() {
        return lastTurnId;
    }
}
