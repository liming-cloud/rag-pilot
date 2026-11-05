package com.liming.rag.answering.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** conversation 表：单表写入用通用方法；带上一轮的查询、按主线递归查轮次、取轮次 id 写在 XML 里。知识库 id 存成 bigint[]。 */
@Mapper
interface ConversationMapper extends BaseMapper<ConversationDO> {

    ConversationDO find(@Param("tenantId") long tenantId, @Param("id") long id);

    List<ConversationDO> listOwned(@Param("tenantId") long tenantId, @Param("userId") long userId);

    long nextTurnId();

    List<ConversationTurnDO> mainLine(@Param("conversationId") long conversationId);
}
