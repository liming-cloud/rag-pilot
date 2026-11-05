package com.liming.rag.answering.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import org.apache.ibatis.type.ArrayTypeHandler;
import org.jspecify.annotations.NullUnmarked;

/** conversation_turn 的一行；轮次 id 先从序列取（{@code nextTurnId}），写入时带上。只在 infrastructure 包内读取。 */
@TableName("conversation_turn")
@NullUnmarked
final class ConversationTurnDO {

    @TableId(type = IdType.INPUT)
    Long id;
    Long conversationId;
    Long parentTurnId;
    String question;
    String status;
    Long answerRecordId;
    @TableField(typeHandler = ArrayTypeHandler.class)
    Long[] knowledgeBaseIds;
    OffsetDateTime askedAt;
    Boolean superseded;

    Long getId() {
        return id;
    }

    Long getConversationId() {
        return conversationId;
    }

    Boolean getSuperseded() {
        return superseded;
    }
}
