package com.liming.rag.identity.infrastructure.security;

import cn.dev33.satoken.secure.BCrypt;
import com.liming.rag.identity.application.auth.PasswordHasher;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * BCrypt 摘要（Sa-Token 自带的实现），默认强度。Sa-Token 把它标为过时，但换实现要引入新依赖（D-016：密码用 Sa-Token 自带的 BCrypt，不新增依赖），
 * 已有的密码摘要也要兼容，所以保留并抑制过时告警（#145），换实现另议。
 */
@SuppressWarnings("deprecation")
@Component
class BCryptPasswordHasher implements PasswordHasher {

    @Override
    public String hash(String password) {
        return BCrypt.hashpw(password, BCrypt.gensalt());
    }

    @Override
    public boolean matches(@Nullable String password, String hash) {
        return BCrypt.checkpw(password == null ? "" : password, hash);
    }
}
