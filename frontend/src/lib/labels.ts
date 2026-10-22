/**
 * 面向界面的文案映射。
 *
 * 后端返回的枚举是给机器看的(`ready` / `keywordOnly` / `failed`),
 * 直接显示在中文界面里既不统一也不好懂,统一在这里翻译。
 */

const INDEX_STATUS: Record<string, string> = {
  ready: '已索引',
  keywordonly: '仅关键词',
  keyword_only: '仅关键词',
  pending: '待索引',
  indexing: '索引中',
  failed: '索引失败',
};

export function indexStatusLabel(value: string | null | undefined): string {
  if (!value) return '';
  return INDEX_STATUS[value.toLowerCase()] ?? value;
}

/** 索引状态的语义色调,用于小圆点/文字着色。 */
export function indexStatusTone(value: string | null | undefined): 'ok' | 'warn' | 'bad' | 'idle' {
  switch ((value ?? '').toLowerCase()) {
    case 'ready':
      return 'ok';
    case 'keywordonly':
    case 'keyword_only':
      return 'warn';
    case 'failed':
      return 'bad';
    default:
      return 'idle';
  }
}
