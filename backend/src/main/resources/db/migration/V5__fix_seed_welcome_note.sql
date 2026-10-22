-- 修复 V2__seed.sql 里的一处数据缺陷(在 M3 验证时发现)。
--
-- V2 用的是标准 SQL 字符串字面量:
--     '...# 欢迎使用 ai-notes\n\n这是一个...'
-- 而 PostgreSQL 在 standard_conforming_strings = on(默认)下**不会**把 '\n' 解释成换行,
-- 存进去的是「反斜杠 + n」两个字符。于是欢迎笔记的正文变成了"一整行",里面 9 处 \n 全是字面量。
--
-- 后果不只是显示难看:Markdown 分块器按行匹配 ATX 标题(# ~ ######),整篇只有一行时,
-- 这一行会全部被当成标题、正文缓冲区为空 → 一个检索片段都生成不出来 → 这篇笔记永远搜不到。
-- (实测:重建索引时它报"成功",但索引里 0 个文档。)
--
-- 这里把字面量 "\n" 还原成真实换行。只动种子数据(content_hash = 'seed'),
-- 以免误伤用户笔记里合法出现的 \n(例如一篇讲转义符的笔记)。
-- 注意:strpos/replace 的入参不做转义转换,'\n' 就是两个字符;E'\n' 才是真正的换行。
UPDATE note
SET content_md   = replace(content_md, '\n', E'\n'),
    -- 正文变了,索引自然失效;置回 pending 让状态与实际一致(重建索引接口会把它刷成 ready)
    index_status = 'pending'
WHERE content_hash = 'seed'
  AND strpos(content_md, '\n') > 0;
