package com.liming.rag.answering.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.liming.rag.answering.application.review.CitationOpenService;
import com.liming.rag.identity.api.CurrentUser;
import com.liming.rag.identity.api.IdentityPermissions;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 员工点开引用时上报（#145 A11）。只有提问人本人能报，别人的回答一律返回 404。 */
@RestController
@SaCheckLogin
@SaCheckPermission(IdentityPermissions.CHAT_USE)
class CitationOpenController {

    private final CitationOpenService service;
    private final CurrentUser currentUser;

    CitationOpenController(CitationOpenService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @PostMapping("/api/answers/{answerId}/citations/{index}/opened")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void opened(@PathVariable long answerId, @PathVariable int index) {
        service.opened(currentUser.tenantId(), currentUser.userId(), answerId, index);
    }
}
