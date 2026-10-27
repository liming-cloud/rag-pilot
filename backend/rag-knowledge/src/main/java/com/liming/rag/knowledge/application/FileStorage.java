package com.liming.rag.knowledge.application;

import com.liming.rag.common.TenantId;

/** 原始文件存储端口。按内容摘要寻址，同一文件只存一份。 */
public interface FileStorage {

    void store(TenantId tenantId, String sha256, byte[] content);

    byte[] read(TenantId tenantId, String sha256);

    /** 删除文件；文件不存在时什么都不做。 */
    void delete(TenantId tenantId, String sha256);
}
