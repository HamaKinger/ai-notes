-- M5 Step 1:工具调用 + 写操作确认。
-- agent_run / tool_invocation 在 V1 就有,这里补上"跨请求续跑"所必需的几列与级联规则。
--
-- 为什么要跨请求:一次运行是**两段 HTTP**——第一段 (POST /workspaces/{ws}/agent/run) 跑到
-- 模型要求写操作時就停下,等用户点「确认」;第二段 (POST /agent/runs/{id}/confirm) 才执行写入
-- 并接着往下跑。两段之间后端可能重启、用户可能过几分钟才点。所以"重建提示词所需的一切"必须落库,
-- 只放在内存里的代价是:重启一次,待确认的卡片就点不动了。
--
--   * call_id  —— 模型给的工具调用 id(tool_call.id)。续跑时要用它把 AssistantMessage 里的
--                 tool_calls 与 ToolResponseMessage 重新对上;对不上服务商会直接拒绝请求。
--   * ord      —— 同一次运行内的顺序。主键是随机 UUID,排不出先后。
--   * requires_confirmation —— 这次调用是不是写操作。工具集会变,回放历史时不该拿"当前工具表"去猜。
--   * summary  —— 轨迹里那一行中文摘要(「新建《周报》」),由调用参数当场算出来并存下,免得回放时再解析一遍参数。
ALTER TABLE agent_run
    ADD COLUMN user_message_id      UUID REFERENCES message (id) ON DELETE CASCADE,
    ADD COLUMN assistant_message_id UUID REFERENCES message (id) ON DELETE CASCADE,
    ADD COLUMN steps                INT NOT NULL DEFAULT 0,
    ADD COLUMN error                TEXT;

ALTER TABLE tool_invocation
    ADD COLUMN call_id                TEXT,
    ADD COLUMN ord                    INT NOT NULL DEFAULT 0,
    ADD COLUMN requires_confirmation  BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN summary                TEXT;

-- V1 里这两条外键都是裸 REFERENCES(没有 ON DELETE),而删会话要走
-- message 的 ON DELETE CASCADE —— 一旦某个会话跑过 Agent,删它就会撞上外键冲突。
-- 运行轨迹是会话的一部分:会话没了,轨迹也该跟着走。
ALTER TABLE agent_run DROP CONSTRAINT agent_run_conversation_id_fkey;
ALTER TABLE agent_run ADD CONSTRAINT agent_run_conversation_id_fkey
    FOREIGN KEY (conversation_id) REFERENCES conversation (id) ON DELETE CASCADE;

ALTER TABLE tool_invocation DROP CONSTRAINT tool_invocation_run_id_fkey;
ALTER TABLE tool_invocation ADD CONSTRAINT tool_invocation_run_id_fkey
    FOREIGN KEY (run_id) REFERENCES agent_run (id) ON DELETE CASCADE;

-- 会话历史要带上"这条回答跑了哪些工具",按 assistant_message_id 一次性查一批
CREATE INDEX ix_agent_run_assistant_message ON agent_run (assistant_message_id);
-- 续跑时按 (运行, 顺序) 取轨迹
CREATE INDEX ix_tool_invocation_run ON tool_invocation (run_id, ord);
