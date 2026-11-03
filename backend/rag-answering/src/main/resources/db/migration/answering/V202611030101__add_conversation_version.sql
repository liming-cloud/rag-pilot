-- 所属模块：rag-answering。
-- 目的：同一会话并发提问时主线不分叉（#135）。会话加版本号做乐观锁，并记下主线最后一轮；主线改为从最后一轮
--       沿 parent_turn_id 往回找，不再按轮次 id 排序（id 在开始生成时分配，先开始、后结束的一轮会排错）。
-- 影响：conversation 加两列，已有会话的版本号为 0，最后一轮取主线上 id 最大的一轮（与改动前的排序一致）。
--       不改动 conversation_turn。
-- 回滚：按 D-048 用升级前的备份回退。不能只换回旧版本的镜像：旧版本提问时不更新 last_turn_id，再升级时主线会停在旧的末尾。
ALTER TABLE conversation ADD COLUMN version bigint NOT NULL DEFAULT 0;

-- 不加外键：删除会话时轮次随之级联删除，最后一轮的 id 不会单独失效。
ALTER TABLE conversation ADD COLUMN last_turn_id bigint;

UPDATE conversation c
SET last_turn_id = (SELECT max(t.id) FROM conversation_turn t WHERE t.conversation_id = c.id AND NOT t.superseded);
