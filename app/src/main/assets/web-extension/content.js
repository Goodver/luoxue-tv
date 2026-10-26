// 洛雪TV 遥控器输入桥 - content script（备用通道）
// 主输入走 GeckoView 原生 PanZoomController（scrollBy/onTouchEvent），
// 本扩展仅作为备用：若原生通道失效可回退到 connectNative 消息。
(function () {
  // v1.39.2：浏览器特征伪装——站点广告 JS 按 window.chrome/platform/vendor 区分"真 Chrome"投广告。
  // 桌面版 app（Electron 45）实测特征：window.chrome 为空对象、platform=Win32、vendor=Google Inc.、hw=16。
  // content script 运行在 isolated world，必须注入 <script> 到页面主世界，且须先于页面脚本执行。
  try {
    (function () {
      var s = document.createElement('script');
      s.type = 'text/javascript';
      s.textContent = '(function(){' +
        'try{if(!window.chrome){window.chrome={};}}catch(e){}' +
        'try{Object.defineProperty(navigator,"vendor",{get:function(){return "Google Inc."}});}catch(e){}' +
        'try{Object.defineProperty(navigator,"platform",{get:function(){return "Win32"}});}catch(e){}' +
        'try{Object.defineProperty(navigator,"hardwareConcurrency",{get:function(){return 16}});}catch(e){}' +
        '})();';
      var p = document.documentElement || document.head || document.body;
      if (p) { p.appendChild(s); try { s.parentNode.removeChild(s); } catch (e) {} }
    })();
  } catch (e) {}

  // v1.39.4：广告容器 CSS 隐藏——4kcz 等 mibt 模板站的固定漂浮广告容器（.ads/.footerad）。
  // 仅隐藏广告容器类，不碰页面布局/脚本/导航；.ads 为影视站模板通用广告位类名。
  try {
    (function () {
      var st = document.createElement('style');
      st.type = 'text/css';
      st.textContent = '.ads,.footerad,.mac_ad{display:none !important;}';
      var p = document.documentElement || document.head;
      if (p) { p.appendChild(st); }
    })();
  } catch (e) {}

  var PORT_NAME = 'gv_bridge';
  var port = null;

  function connect() {
    try {
      console.log('GVB: connectNative try ' + PORT_NAME);
      port = browser.runtime.connectNative(PORT_NAME);
      console.log('GVB: connectNative ok port=' + !!port);
    } catch (e) {
      console.log('GVB: connectNative FAIL ' + e);
      port = null;
      return;
    }
    if (port) {
      console.log('GVB: port attached');
      port.onMessage.addListener(function (msg) {
        if (!msg || !msg.type) return;
        try {
          if (msg.type === 'click') clickAt(msg.x, msg.y);
          else if (msg.type === 'rightClick') rightClickAt(msg.x, msg.y);
          else if (msg.type === 'scroll') scrollAt(msg.x, msg.y, msg.dx, msg.dy);
          else if (msg.type === 'hover') hoverAt(msg.x, msg.y);
          else if (msg.type === 'dragStart') dragStart(msg.x, msg.y);
          else if (msg.type === 'dragMove') dragMove(msg.x, msg.y);
          else if (msg.type === 'dragEnd') dragEnd(msg.x, msg.y);
        } catch (e) {}
      });
    }
  }

  try { connect(); console.log('GVB: connect called on load'); } catch (e) { console.log('GVB: load connect EXC ' + e); }
  window.addEventListener('pageshow', function () {
    if (!port) { console.log('GVB: pageshow reconnect'); connect(); }
  });

  // v1.38n：劫持 window.open——电视端无新窗口概念，JS 打开播放页一律改当前页导航
  // （简云影视等 MacCms 站播放按钮常 window.open 播放页，不开新窗则无反应）
  try {
    window.open = function (u, n, f) {
      if (u && typeof u === 'string') { try { location.href = u; } catch (e) {} }
      return null;
    };
  } catch (e) {}

  function cssPos(px, py) {
    var d = window.devicePixelRatio || 1;
    return { x: px / d, y: py / d };
  }

  function clickAt(px, py) {
    var p = cssPos(px, py);
    var el = document.elementFromPoint(p.x, p.y);
    if (!el) return;
    var t = el;
    for (var i = 0; i < 8 && t; i++, t = t.parentElement) {
      if (t.tagName === 'A' || t.tagName === 'BUTTON' || t.tagName === 'INPUT'
        || t.tagName === 'SELECT'
        || (t.getAttribute && (t.getAttribute('role') === 'button' || t.getAttribute('onclick')))) {
        break;
      }
    }
    if (!t) t = el;
    var r = t.getBoundingClientRect();
    var cx = r.left + r.width / 2;
    var cy = r.top + r.height / 2;
    function fire(n, o) { try { t.dispatchEvent(new MouseEvent(n, o)); } catch (e) {} }
    var o = { bubbles: true, cancelable: true, clientX: cx, clientY: cy, view: window, button: 0, buttons: 1 };
    fire('pointerdown', o); fire('mousedown', o);
    fire('pointerup', o); fire('mouseup', o); fire('click', o);
    try {
      var tc = new Touch({ identifier: 1, target: t, clientX: cx, clientY: cy });
      t.dispatchEvent(new TouchEvent('touchstart', { bubbles: true, cancelable: true, touches: [tc], targetTouches: [tc], changedTouches: [tc] }));
      t.dispatchEvent(new TouchEvent('touchend', { bubbles: true, cancelable: true, touches: [], targetTouches: [], changedTouches: [tc] }));
    } catch (e) {}
    // v1.38c：A 链接强制当前页导航兜底（去掉 !t.target 限制）
    // MacCms 系站点（简云影视等）卡片常带 target="_blank"：
    // 合成事件(非可信手势)点 _blank 链接会被浏览器拦截打开新窗口，导致"点了不跳"。
    // location.href 是页面自身导航，任何 JS 都能执行，不受手势/信任限制 → 一律改 _self 强制跳。
    if (t.tagName === 'A' && t.href) {
      try {
        if (t.target && t.target !== '_self') { try { t.target = '_self'; } catch (e) {} }
        if (location.href.split('#')[0] !== t.href.split('#')[0]) location.href = t.href;
      } catch (e) {}
    }
  }

  function rightClickAt(px, py) {
    var p = cssPos(px, py);
    var el = document.elementFromPoint(p.x, p.y);
    if (!el) return;
    var t = el;
    for (var i = 0; i < 8 && t; i++, t = t.parentElement) {
      if (t.tagName === 'A' || t.tagName === 'BUTTON' || t.tagName === 'INPUT'
        || t.tagName === 'SELECT'
        || (t.getAttribute && (t.getAttribute('oncontextmenu') || t.getAttribute('role') === 'button'))) {
        break;
      }
    }
    if (!t) t = el;
    var r = t.getBoundingClientRect();
    var cx = r.left + r.width / 2;
    var cy = r.top + r.height / 2;
    function fire(n, o) { try { t.dispatchEvent(new MouseEvent(n, o)); } catch (e) {} }
    var base = { bubbles: true, cancelable: true, clientX: cx, clientY: cy, view: window, button: 2, buttons: 2 };
    fire('mousedown', base);
    fire('mouseup', { bubbles: true, cancelable: true, clientX: cx, clientY: cy, view: window, button: 2, buttons: 0 });
    fire('contextmenu', { bubbles: true, cancelable: true, clientX: cx, clientY: cy, view: window, button: 2, buttons: 0 });
  }

  function scrollAt(px, py, dx, dy) {
    var p = cssPos(px, py);
    var el = document.elementFromPoint(p.x, p.y);
    if (!el) return;
    var t = el, s = null;
    for (var i = 0; i < 10 && t; i++, t = t.parentElement) {
      if (t.scrollHeight > t.clientHeight + 10 || t.scrollWidth > t.clientWidth + 10) { s = t; break; }
    }
    if (s) { s.scrollBy(dx, dy); }
    else { window.scrollBy(dx, dy); document.documentElement.scrollBy(dx, dy); }
  }

  function hoverAt(px, py) {
    var p = cssPos(px, py);
    var el = document.elementFromPoint(p.x, p.y);
    if (!el) return;
    el.dispatchEvent(new MouseEvent('mouseover', { bubbles: true }));
    el.dispatchEvent(new MouseEvent('mouseenter', { bubbles: false }));
  }

  var g = null;
  function dragStart(px, py) {
    var p = cssPos(px, py);
    var el = document.elementFromPoint(p.x, p.y);
    if (!el) return;
    var tc = new Touch({ identifier: 2, target: el, clientX: p.x, clientY: p.y });
    el.dispatchEvent(new TouchEvent('touchstart', { bubbles: true, cancelable: true, touches: [tc], targetTouches: [tc], changedTouches: [tc] }));
    g = { active: true, el: el, id: 2 };
  }

  function dragMove(px, py) {
    if (!g || !g.active) return;
    var p = cssPos(px, py);
    var tc = new Touch({ identifier: g.id, target: g.el, clientX: p.x, clientY: p.y });
    g.el.dispatchEvent(new TouchEvent('touchmove', { bubbles: true, cancelable: true, touches: [tc], targetTouches: [tc], changedTouches: [tc] }));
  }

  function dragEnd(px, py) {
    if (!g || !g.active) return;
    var p = cssPos(px, py);
    var tc = new Touch({ identifier: g.id, target: g.el, clientX: p.x, clientY: p.y });
    g.el.dispatchEvent(new TouchEvent('touchend', { bubbles: true, cancelable: true, touches: [], targetTouches: [], changedTouches: [tc] }));
    g.active = false;
  }
})();
