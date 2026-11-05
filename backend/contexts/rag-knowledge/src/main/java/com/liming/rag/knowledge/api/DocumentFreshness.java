package com.liming.rag.knowledge.api;

import java.time.OffsetDateTime;

/**
 * 文档的新旧情况，用来标出引用的资料是否过期。
 *
 * @param uploadedAt 上传时间；新版本是新上传的文档，所以也是这一版的更新时间
 * @param expired 失效日期已过
 */
public record DocumentFreshness(OffsetDateTime uploadedAt, boolean expired) {
}
