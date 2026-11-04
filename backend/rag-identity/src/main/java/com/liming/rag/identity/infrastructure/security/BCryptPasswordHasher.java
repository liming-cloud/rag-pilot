package com.liming.rag.identity.infrastructure.security;

import cn.dev33.satoken.secure.BCrypt;
import com.liming.rag.identity.application.PasswordHasher;
import org.springframework.stereotype.Component;

/** BCrypt 摘要（Sa-Token 自带的实现），默认强度。 */
@Component
class BCryptPasswordHasher implements PasswordHasher {

    @Override
    public String hash(String password) {
        return BCrypt.hashpw(password, BCrypt.gensalt());
    }

    @Override
    public boolean matches(String password, String hash) {
        return BCrypt.checkpw(password == null ? "" : password, hash);
    }
}
