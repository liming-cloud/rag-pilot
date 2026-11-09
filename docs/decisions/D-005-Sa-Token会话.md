# D-005 Sa-Token 会话先存内存（2026-10-25）

试点单实例部署，会话存在内存，应用重启后需要重新登录。需要多实例时改用 Redis 和 `sa-token-redis-template`。

（已由 D-053 修订：会话经 Redisson 存进 Redis。）
