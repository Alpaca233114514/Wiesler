// FOUC 预防脚本：首屏样式解析前同步持久化的外观主题到 <meta name="color-scheme">。
// Synchronous FOUC-prevention script reflecting saved color-scheme preference before paint.
(() => {
  'use strict';
  try {
    const stored = window.localStorage?.getItem('wiesler.theme');
    const meta = document.querySelector('meta[name="color-scheme"]');
    if (meta) {
      meta.content = stored === 'light' || stored === 'dark' ? stored : 'light dark';
    }
  } catch (_) {}
})();
