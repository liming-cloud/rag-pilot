package com.liming.rag.answering.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import org.apache.ibatis.type.ArrayTypeHandler;
import org.jspecify.annotations.NullUnmarked;

/**
 * answer_record 的一行，加载聚合和单表写入用（问题向量、创建时间不在这里，由数据库和后台任务写）。只在 infrastructure 包内读取。
 */
@TableName(value = "answer_record", autoResultMap = true)
@NullUnmarked
final class AnswerRecordDO {

    @TableId(type = IdType.AUTO)
    Long id;
    Long tenantId;
    Long userId;
    String question;
    String answerText;
    Boolean refused;
    String refusalReason;
    Double topRelevance;
    String rewrittenQuestion;
    @TableField(typeHandler = ArrayTypeHandler.class)
    Long[] knowledgeBaseIds;
    String rating;
    String feedbackComment;
    OffsetDateTime ratedAt;
    String reviewStatus;
    String resolutionNote;
    Long resolvedBy;
    OffsetDateTime resolvedAt;

    Long getId() {
        return id;
    }

    Long getTenantId() {
        return tenantId;
    }

    String getRating() {
        return rating;
    }

    String getFeedbackComment() {
        return feedbackComment;
    }

    OffsetDateTime getRatedAt() {
        return ratedAt;
    }

    String getReviewStatus() {
        return reviewStatus;
    }

    String getResolutionNote() {
        return resolutionNote;
    }

    Long getResolvedBy() {
        return resolvedBy;
    }

    OffsetDateTime getResolvedAt() {
        return resolvedAt;
    }
}
