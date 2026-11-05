package com.liming.rag.settings.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import org.jspecify.annotations.NullUnmarked;

/** model_provider 的一行：单表读写经 {@link ModelProviderMapper}，只在 infrastructure 包内读取。 */
@TableName("model_provider")
@NullUnmarked
final class ModelProviderDO {

    @TableId(type = IdType.AUTO)
    Long id;
    String name;
    String baseUrl;
    /** 为 null 时更新不改这一列（MyBatis-Plus 默认跳过 null 字段），即保留原来的密钥。 */
    byte[] apiKeyCipher;
    String apiKeyLast4;
    OffsetDateTime updatedAt;

    Long getId() {
        return id;
    }

    String getName() {
        return name;
    }
}
