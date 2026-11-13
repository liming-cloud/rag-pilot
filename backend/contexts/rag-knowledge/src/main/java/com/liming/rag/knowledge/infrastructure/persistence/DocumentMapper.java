package com.liming.rag.knowledge.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 资料体检信号的写入见 {@code mapper/knowledge/DocumentMapper.xml}（#218）。 */
@Mapper
public interface DocumentMapper extends BaseMapper<DocumentDO> {

    /** 写入读取时得到的信号，并从文档的分块算出其余信号。 */
    void refreshSignals(@Param("tenantId") long tenantId, @Param("documentId") long documentId,
            @Param("images") int images, @Param("largestTableRows") int largestTableRows);

    /** 删掉这份文档作为任一方的疑似重复记录。 */
    void deleteOverlaps(@Param("tenantId") long tenantId, @Param("documentId") long documentId);

    /** 和租户里其他文档比较分块正文，记下两个方向的疑似重复。 */
    void insertOverlaps(@Param("tenantId") long tenantId, @Param("documentId") long documentId);
}
