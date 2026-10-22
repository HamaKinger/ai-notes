-- M4:AI 对话(RAG 问答)。表在 V1 就建好了,这里只补两处当时没法预料的字段。

-- 引用(citation)要能显示成「笔记标题 › 章节路径」并且点了跳进正文那一节,
-- 光有 note_id / chunk_id 不够:
--   1. 标题会改、笔记还会被软删除,回查 note 表拿到的可能已经不是回答问题时的样子;
--   2. 跳转复用全文检索那套**文本锚点**(lib/locate.ts),需要章节路径 + 一段原文才能定位。
-- 所以生成回答时把这几样一并当**快照**存下来,渲染引用时一次查询搞定,不回查 ES。
ALTER TABLE citation ADD COLUMN ord          INT;
ALTER TABLE citation ADD COLUMN note_title   TEXT;
ALTER TABLE citation ADD COLUMN heading_path TEXT;
ALTER TABLE citation ADD COLUMN snippet      TEXT;

-- 会话列表按"最近说过话"排序:只用 created_at 的话,常用的老会话会被新会话一直往下顶。
ALTER TABLE conversation ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
