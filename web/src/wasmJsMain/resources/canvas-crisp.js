/* canvas 与设备像素严格 1:1 —— 消掉 Compose Web 在分数 DPR 下的"永久微微糊"。
 *
 * 为什么需要它（2026-10-09 实测，浏览器 150% 缩放）：
 *   dpr = 1.5；CMP/skiko 给 canvas 的 backing store = floor(innerWidth*dpr) × floor(innerHeight*dpr)
 *   = 853×1255（用的是**整数** innerWidth/innerHeight），而 shadow 里 `canvas{width:100%;height:100%}`
 *   解析到**真实（分数）视口** 569.33×837.65 CSS px = 854×1256.47 设备像素
 *   ⇒ 位图被拉伸 1.0012 倍 ⇒ 双线性重采样 ⇒ 文字笔画发虚。
 * 做法：只把 canvas 的 CSS 尺寸钉成 backing/dpr（布局只缩 ≤1 设备像素，肉眼不可见）⇒ 1 位图像素 = 1 设备像素。
 * 不碰 CMP：CMP 若重算尺寸，本守卫会把它再拉回不变量（收敛，不振荡）。
 * ⚠️ pinch（触摸板放大）是另一条通路：dpr 与 innerWidth 都不变 ⇒ 上面两条都不会触发，
 *    所以单独监听 visualViewport（CMP 0 处理 —— 产物里 visualViewport 出现 0 次）。
 */
(function () {
  var findCanvas = function () {
    var found = null;
    (function walk(root) {
      root.querySelectorAll('canvas').forEach(function (c) { if (!found) found = c; });
      root.querySelectorAll('*').forEach(function (e) { if (e.shadowRoot) walk(e.shadowRoot); });
    })(document);
    return found;
  };

  var current = null;
  var pin = function (c) {
    var dpr = window.devicePixelRatio || 1;
    // 全精度的小数 px（不要四舍五入：round 会把设备像素又推成非整数）
    var w = c.width / dpr + 'px', h = c.height / dpr + 'px';
    if (c.style.width !== w) c.style.width = w;
    if (c.style.height !== h) c.style.height = h;
  };

  var pending = false;
  var sync = function () {
    if (pending) return;
    pending = true;
    requestAnimationFrame(function () {
      pending = false;
      var c = findCanvas();
      if (c !== current) {
        current = c;
        if (c && window.ResizeObserver) {
          // 同时盯 canvas 自己与它的父节点：
          //  * 父节点 —— 布局变了（窗口/容器尺寸变了）要让 canvas 重新对齐；
          //  * canvas 自己 —— 有东西改写了它的 CSS 尺寸（CMP 重算、或任何外部样式）时，
          //    它的**布局盒**会变，ResizeObserver 就会回火。
          //    ⚠️ 光盯父节点是不够的：canvas 在一个子 DIV 的 **shadow root** 里，
          //    普通的 MutationObserver **穿不过 shadow 边界**，所以「改在影子树里、且没有布局变化」
          //    的那种改写只有盯 canvas 自己才能发现（2026-10-10 实测：只盯父节点时，
          //    把 style.width 改成错的之后 600ms 内不会被拉回）。
          //    不会振荡：pin() 只在值真变了才写，写完再回火时算出来的是同一个值。
          var ro = new ResizeObserver(sync);
          ro.observe(c);
          if (c.parentElement) ro.observe(c.parentElement);
        }
      }
      if (current) pin(current);
    });
  };

  // 1) 视口/布局变化
  if (window.ResizeObserver) new ResizeObserver(sync).observe(document.documentElement);
  window.addEventListener('resize', sync);

  // 2) DPR 变化（Ctrl+滚轮页面缩放、换屏）：matchMedia 的 change 是唯一可靠信号，且必须**重新注册**
  var watchDpr = function () {
    var dpr = window.devicePixelRatio || 1;
    var mq = window.matchMedia('(resolution: ' + dpr + 'dppx)');
    var on = function () { mq.removeEventListener('change', on); sync(); watchDpr(); };
    mq.addEventListener('change', on);
  };
  watchDpr();

  // 3) pinch（视觉视口缩放）：稳定后补一枪重绘/对齐（不然永远糊着，只能手动刷新）
  if (window.visualViewport) {
    var t = null;
    var onVv = function () { if (t) clearTimeout(t); t = setTimeout(function () { t = null; sync(); }, 150); };
    window.visualViewport.addEventListener('resize', onVv);
    window.visualViewport.addEventListener('scroll', onVv);
  }

  // 4) canvas 被重建 / CMP 重写 style 时兜住
  if (window.MutationObserver) {
    new MutationObserver(sync).observe(document.body, {
      childList: true, subtree: true, attributes: true, attributeFilter: ['style', 'width', 'height']
    });
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', sync); else sync();
})();
