(() => {
  'use strict';
  const appWindow = window.__TAURI__?.window?.getCurrentWindow();
  if (!appWindow) return;
  const header = document.querySelector('header');
  if (!header) return;
  document.documentElement.classList.add('native-window');
  header.classList.add('window-drag-header');
  header.setAttribute('data-tauri-drag-region', '');
  const controls = document.createElement('div');
  controls.className = 'window-controls';
  controls.setAttribute('aria-label', '窗口控制');
  const status = document.createElement('span');
  status.className = 'window-action-status'; status.setAttribute('role', 'status'); status.hidden = true;
  header.append(status, controls);
  const svgNS = 'http://www.w3.org/2000/svg';
  function icon(path) {
    const svg = document.createElementNS(svgNS, 'svg');
    svg.setAttribute('viewBox', '0 0 16 16'); svg.setAttribute('aria-hidden', 'true');
    const p = document.createElementNS(svgNS, 'path');p.setAttribute('d', path);svg.append(p);return svg;
  }
  async function act(action) {
    status.hidden = true;
    try { await action(); }
    catch (_) { status.textContent = '窗口操作失败，请重试'; status.hidden = false; }
  }
  function button(id, label, path, action) {
    const el = document.createElement('button');el.id = id;el.type = 'button';
    el.title = label; el.setAttribute('aria-label', label);el.append(icon(path));
    el.onclick = () => act(action);controls.append(el);return el;
  }
  button('window-minimize', '最小化', 'M3 8.5h10', () => appWindow.minimize());
  const maximize = button('window-maximize', '最大化', 'M3.5 3.5h9v9h-9z', async () => {
    await appWindow.toggleMaximize();await syncMaximized();
  });
  button('window-close', '关闭', 'M3 3l10 10M13 3L3 13', () => appWindow.close());
  async function syncMaximized() {
    try {
      const isMaximized = await appWindow.isMaximized();
      const label = isMaximized ? '还原' : '最大化';
      maximize.title = label;maximize.setAttribute('aria-label', label);
      maximize.replaceChildren(icon(isMaximized ? 'M5.5 5.5h7v7h-7zM3.5 10V3.5H10' : 'M3.5 3.5h9v9h-9z'));
    } catch (_) { /* 保留可用操作，不伪造窗口状态。 / Keep controls usable if state is unavailable. */ }
  }
  window.addEventListener('resize', syncMaximized);
  syncMaximized();
})();
