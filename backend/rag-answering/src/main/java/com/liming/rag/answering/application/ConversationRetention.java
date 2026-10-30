package com.liming.rag.answering.application;

import com.liming.rag.answering.domain.ConversationRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 员工端会话的保留期（D-036）：从最后一次提问起超过 {@code rag.answering.conversation.retention} 的会话每天清理一次。
 * 只删会话和轮次，回答记录照常保留供审计。
 */
@Component
public class ConversationRetention {

    private static final Logger LOG = LoggerFactory.getLogger(ConversationRetention.class);

    private final ConversationRepository conversations;
    private final AnsweringProperties.Conversation settings;

    ConversationRetention(ConversationRepository conversations, AnsweringProperties properties) {
        this.conversations = conversations;
        this.settings = properties.conversation();
    }

    /** 每天凌晨 3 点（服务器时区）清理一次，返回删除的会话数。 */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public int purge() {
        int deleted = conversations.deleteInactiveSince(Instant.now().minus(settings.retention()));
        if (deleted > 0) {
            LOG.info("清理了 {} 个超过保留期的会话", deleted);
        }
        return deleted;
    }
}
