// Runs inside every chapter page. Talks to the app through the `Android` JavaScript interface.
(function () {
  'use strict';
  var init = window.READER_INIT || {};
  var A = window.Android || {
    onPage: function () {}, onTap: function () {}, nextChapter: function () {},
    prevChapter: function () {}, openLink: function () {}, openExternal: function () {}
  };
  var root = document.documentElement;
  root.style.visibility = 'hidden';

  var page = 0, count = 1, W = window.innerWidth, ready = false;
  var scroller = function () { return document.scrollingElement || root; };

  function measure() {
    W = window.innerWidth;
    count = Math.max(1, Math.ceil((scroller().scrollWidth - 2) / W));
  }

  function show(p) {
    page = Math.max(0, Math.min(count - 1, p));
    scroller().scrollLeft = page * W;
    A.onPage(page, count);
  }

  function next() { if (page < count - 1) show(page + 1); else A.nextChapter(); }
  function prev() { if (page > 0) show(page - 1); else A.prevChapter(); }

  function pageOf(el) {
    if (!el) return 0;
    var rect = el.getBoundingClientRect();
    return Math.floor((rect.left + scroller().scrollLeft + 1) / W);
  }

  function start() {
    if (ready) return;
    ready = true;
    measure();
    var p = 0;
    if (init.atEnd) p = count - 1;
    else if (init.anchor) p = pageOf(document.getElementById(init.anchor));
    else if (init.fraction) p = Math.floor(init.fraction * count + 0.0001);
    show(p);
    root.style.visibility = 'visible';
  }

  function whenLaidOut() {
    var fonts = document.fonts && document.fonts.ready ? document.fonts.ready : Promise.resolve();
    fonts.then(function () { requestAnimationFrame(start); });
  }
  if (document.readyState === 'complete') whenLaidOut(); else window.addEventListener('load', whenLaidOut);
  setTimeout(start, 2500); // never leave the page hidden if an image hangs

  window.addEventListener('resize', function () {
    if (!ready) return;
    var fraction = page / count;
    measure();
    show(Math.floor(fraction * count + 0.0001));
  });

  // Selection handles can drag the hidden overflow sideways; snap back to a whole page.
  var touching = false, snapTimer = 0;
  window.addEventListener('scroll', function () {
    clearTimeout(snapTimer);
    snapTimer = setTimeout(function () {
      if (touching || !ready) return;
      var p = Math.round(scroller().scrollLeft / W);
      if (scroller().scrollLeft !== p * W || p !== page) show(p);
    }, 150);
  }, { passive: true });

  function hasSelection() {
    var s = window.getSelection();
    return !!s && !s.isCollapsed && s.toString().trim().length > 0;
  }

  // Taps: left third = back, right third = forward, middle = show/hide the toolbar. Horizontal swipes turn pages.
  var sx = 0, sy = 0, st = 0, swiped = false, hadSelection = false;
  document.addEventListener('touchstart', function (e) {
    if (e.touches.length !== 1) return;
    touching = true;
    sx = e.touches[0].clientX; sy = e.touches[0].clientY; st = Date.now();
    swiped = false;
    hadSelection = hasSelection();
  }, { passive: true });

  document.addEventListener('touchend', function (e) {
    touching = false;
    if (hadSelection || hasSelection()) return;
    var t = e.changedTouches[0];
    var dx = t.clientX - sx, dy = t.clientY - sy;
    if (Math.abs(dx) > 50 && Math.abs(dx) > Math.abs(dy) * 1.5 && Date.now() - st < 800) {
      swiped = true;
      if (dx < 0) next(); else prev();
    }
  }, { passive: true });

  document.addEventListener('click', function (e) {
    if (swiped) { swiped = false; return; }
    var a = e.target.closest ? e.target.closest('a[href]') : null;
    if (a) { e.preventDefault(); followLink(a.getAttribute('href')); return; }
    if (hadSelection) { hadSelection = false; return; }
    var x = e.clientX / W;
    if (x < 0.3) prev(); else if (x > 0.7) next(); else A.onTap();
  }, true);

  function followLink(href) {
    if (!href) return;
    if (href.charAt(0) === '#') {
      var el = document.getElementById(decodeURIComponent(href.slice(1)));
      if (el) show(pageOf(el)); else A.openLink(href);
      return;
    }
    var url;
    try { url = new URL(href, location.href); } catch (err) { return; }
    if (url.host !== location.host) { A.openExternal(url.href); return; }
    if (url.pathname === location.pathname && url.hash) {
      var target = document.getElementById(decodeURIComponent(url.hash.slice(1)));
      if (target) { show(pageOf(target)); return; }
    }
    A.openLink(url.href);
  }

  // The selection is gone by the time the app asks for it (closing the menu clears it), so keep the last one.
  var lastSelection = null;
  function blockOf(node) {
    var el = node.nodeType === 3 ? node.parentElement : node;
    return (el && el.closest && el.closest('p,li,blockquote,dd,dt,td,th,h1,h2,h3,h4,h5,h6,div,section')) || el || document.body;
  }
  function excerpt(text, around, max) {
    text = text.replace(/\s+/g, ' ').trim();
    if (text.length <= max) return text;
    var i = Math.max(0, text.indexOf(around));
    var from = Math.max(0, i - Math.floor((max - around.length) / 2));
    var to = Math.min(text.length, from + max);
    return (from > 0 ? '… ' : '') + text.slice(from, to).trim() + (to < text.length ? ' …' : '');
  }
  document.addEventListener('selectionchange', function () {
    var s = window.getSelection();
    if (!s || s.isCollapsed || !s.rangeCount) return;
    var text = s.toString().replace(/\s+/g, ' ').trim();
    if (!text) return;
    var block = blockOf(s.getRangeAt(0).commonAncestorContainer);
    var context = excerpt(block.innerText || block.textContent || '', text, 1500);
    lastSelection = { text: text, context: context === text ? '' : context };
  });

  function pageText() {
    var pad = parseFloat(getComputedStyle(root).getPropertyValue('--pad-x')) || 20;
    var top = parseFloat(getComputedStyle(root).getPropertyValue('--pad-top')) || 40;
    var bottom = parseFloat(getComputedStyle(root).getPropertyValue('--pad-bottom')) || 40;
    var a = document.caretRangeFromPoint(pad + 1, top + 1);
    var b = document.caretRangeFromPoint(W - pad - 1, window.innerHeight - bottom - 1);
    var text = '';
    if (a && b) {
      var r = document.createRange();
      r.setStart(a.startContainer, a.startOffset);
      r.setEnd(b.startContainer, b.startOffset);
      text = r.toString();
    }
    if (!text.trim()) text = (document.body.innerText || '').slice(0, 3000);
    return text.replace(/[ \t]+/g, ' ').replace(/\n{3,}/g, '\n\n').trim().slice(0, 4000);
  }

  window.reader = {
    next: next,
    prev: prev,
    info: function () { return JSON.stringify({ page: page, count: count }); },
    selectionInfo: function () {
      var info = lastSelection || { text: '', context: '' };
      lastSelection = null;
      return JSON.stringify(info);
    },
    clearSelection: function () { var s = window.getSelection(); if (s) s.removeAllRanges(); },
    pageText: pageText
  };
})();
