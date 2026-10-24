// 洛雪TV 遥控器输入桥 - content script（备用通道）
// 主输入走 GeckoView 原生 PanZoomController（scrollBy/onTouchEvent），
// 本扩展仅作为备用：若原生通道失效可回退到 connectNative 消息。
(function () {
  var PORT_NAME = 'gv_bridge';
  var port = null;

  function connect() {
    try {
      port = browser.runtime.connectNative(PORT_NAME);
    } catch (e) {
      port = null;
      return;
    }
    if (port) {
      port.onMessage.addListener(function (msg) {
        if (!msg || !msg.type) return;
        try {
          if (msg.type === 'click') clickAt(msg.x, msg.y);
          else if (msg.type === 'scroll') scrollAt(msg.x, msg.y, msg.dx, msg.dy);
          else if (msg.type === 'hover') hoverAt(msg.x, msg.y);
          else if (msg.type === 'dragStart') dragStart(msg.x, msg.y);
          else if (msg.type === 'dragMove') dragMove(msg.x, msg.y);
          else if (msg.type === 'dragEnd') dragEnd(msg.x, msg.y);
        } catch (e) {}
      });
    }
  }

  try { connect(); } catch (e) {}
  window.addEventListener('pageshow', function () {
    if (!port) connect();
  });

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
    if (t.tagName === 'A' && t.href && !t.target) {
      try {
        if (t.href && location.href.split('#')[0] !== t.href.split('#')[0]) location.href = t.href;
      } catch (e) {}
    }
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
