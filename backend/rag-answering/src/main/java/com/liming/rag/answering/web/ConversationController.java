package com.liming.rag.answering.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.liming.rag.answering.application.ConversationService;
import com.liming.rag.answering.application.ConversationViews;
import com.liming.rag.identity.api.CurrentUser;
import com.liming.rag.identity.api.Permissions;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 员工端会话（#107、D-036）。要登录，只能操作自己的会话；别人的会话一律返回 404。
 * 按会话提问和停止生成见 {@link ConversationTurnController}。
 */
@RestController
@RequestMapping("/api/conversations")
@SaCheckLogin
@SaCheckPermission(Permissions.CHAT_USE)
class ConversationController {

    private final ConversationService conversations;
    private final CurrentUser currentUser;

    ConversationController(ConversationService conversations, CurrentUser currentUser) {
        this.conversations = conversations;
        this.currentUser = currentUser;
    }

    /** @param knowledgeBaseIds 可以不传：选中全部已授权的库 */
    record CreateRequest(List<Long> knowledgeBaseIds) {
    }

    /** 两项都可以不传；knowledgeBaseIds 从下一个问题起生效。 */
    record UpdateRequest(String title, List<Long> knowledgeBaseIds) {
    }

    @GetMapping
    List<ConversationViews.Summary> list() {
        return conversations.list(currentUser.tenantId(), currentUser.userId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ConversationViews.Summary create(@RequestBody(required = false) CreateRequest request) {
        return conversations.create(currentUser.tenantId(), currentUser.userId(),
                request == null ? null : request.knowledgeBaseIds());
    }

    @GetMapping("/{id}")
    ConversationViews.Detail detail(@PathVariable long id) {
        return conversations.detail(currentUser.tenantId(), currentUser.userId(), id);
    }

    @PatchMapping("/{id}")
    ConversationViews.Summary update(@PathVariable long id, @RequestBody UpdateRequest request) {
        return conversations.update(currentUser.tenantId(), currentUser.userId(), id, request.title(),
                request.knowledgeBaseIds());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable long id) {
        conversations.delete(currentUser.tenantId(), currentUser.userId(), id);
    }
}
