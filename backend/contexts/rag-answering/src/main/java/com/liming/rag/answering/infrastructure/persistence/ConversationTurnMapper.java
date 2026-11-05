package com.liming.rag.answering.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** conversation_turn 表的单表写入；按主线查轮次是递归查询，在 {@link ConversationMapper} 里。 */
@Mapper
interface ConversationTurnMapper extends BaseMapper<ConversationTurnDO> {
}
