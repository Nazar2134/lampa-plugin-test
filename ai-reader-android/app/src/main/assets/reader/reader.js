// Runs inside every chapter page. Talks to the app through the `Android` JavaScript interface.
(function () {
  'use strict';
  var init = window.READER_INIT || {};
  var A = window.Android || {
    onPage: function () {}, onTap: function () {}, nextChapter: function () {}, prevChapter: function () {},
    openLink: function () {}, openExternal: function () {}, onHighlightTap: function () {}
  };
  var root = document.documentElement;
  root.style.visibility = 'hidden';

  var page = 0, count = 1, W = window.innerWidth, ready = false;
  var scroller = function () { return document.scrollingElement || root; };
  function cssNumber(name, fallback) { return parseFloat(getComputedStyle(root).getPropertyValue(name)) || fallback; }

  // ---------------------------------------------------------------------------------------------
  // Text map: the chapter's text with whitespace collapsed (as the app normalizes it), and for every
  // character the text node and offset it came from. Anchors are {text, occurrence} in this string.

  var map = null;
  function isSpace(c) { return c === 32 || (c >= 9 && c <= 13) || c === 160 || c === 0x2007 || c === 0x202F || (c >= 0x2000 && c <= 0x200A); }

  function textMap() {
    if (map) return map;
    var nodes = [], nodeOf = [], offsetOf = [], chars = [], first = new Map();
    var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {
      acceptNode: function (n) {
        var p = n.parentElement;
        return p && p.closest('script,style,noscript,title') ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT;
      }
    });
    var lastSpace = true, n;
    while ((n = walker.nextNode())) {
      var k = nodes.length;
      nodes.push(n);
      first.set(n, chars.length);
      var t = n.nodeValue;
      for (var i = 0; i < t.length; i++) {
        var c = t.charCodeAt(i);
        if (c === 0xAD) continue;
        if (isSpace(c)) {
          if (lastSpace) continue;
          lastSpace = true;
          chars.push(' ');
        } else {
          lastSpace = false;
          chars.push(t.charAt(i));
        }
        nodeOf.push(k);
        offsetOf.push(i);
      }
    }
    map = { text: chars.join(''), nodes: nodes, nodeOf: nodeOf, offsetOf: offsetOf, first: first };
    return map;
  }

  function normalize(s) {
    var out = '', lastSpace = true;
    for (var i = 0; i < s.length; i++) {
      var c = s.charCodeAt(i);
      if (c === 0xAD) continue;
      if (isSpace(c)) { if (!lastSpace) out += ' '; lastSpace = true; } else { out += s.charAt(i); lastSpace = false; }
    }
    return out.replace(/ $/, '');
  }

  function rangeAt(start, length) {
    var m = textMap();
    if (start < 0 || length <= 0 || start + length > m.text.length) return null;
    var end = start + length - 1;
    var r = document.createRange();
    r.setStart(m.nodes[m.nodeOf[start]], m.offsetOf[start]);
    r.setEnd(m.nodes[m.nodeOf[end]], m.offsetOf[end] + 1);
    return r;
  }

  // Start index of the n-th non-overlapping occurrence; the last one if there are fewer; -1 if none.
  function nthIndex(haystack, needle, n) {
    var i = -1, from = 0, last = -1;
    for (var k = 0; k <= n; k++) {
      i = haystack.indexOf(needle, from);
      if (i < 0) return last;
      last = i;
      from = i + needle.length;
    }
    return i;
  }

  function locate(anchor) {
    if (!anchor || !anchor.text) return null;
    var m = textMap(), needle = normalize(anchor.text), occ = anchor.occurrence || 0;
    var i = nthIndex(m.text, needle, occ);
    if (i < 0) i = nthIndex(m.text.toLowerCase(), needle.toLowerCase(), occ);
    return i < 0 ? null : rangeAt(i, needle.length);
  }

  function occurrencesBefore(text, index) {
    var m = textMap(), n = 0, from = 0;
    while (true) {
      var i = m.text.indexOf(text, from);
      if (i < 0 || i >= index) return n;
      n++;
      from = i + text.length;
    }
  }

  // Map index of a DOM boundary point.
  function indexOfPoint(node, offset) {
    var m = textMap();
    if (node.nodeType !== 3) {
      var after = node.childNodes[offset] || null;
      var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
      walker.currentNode = after || node;
      var t = after && after.nodeType === 3 ? after : walker.nextNode();
      if (!t) return m.text.length;
      node = t; offset = 0;
    }
    var start = m.first.get(node);
    if (start === undefined) return -1;
    var k = m.nodeOf[start], i = start;
    while (i < m.nodeOf.length && m.nodeOf[i] === k && m.offsetOf[i] < offset) i++;
    return i;
  }

  function anchorFor(range) {
    var text = normalize(range.toString()).trim();
    if (!text) return null;
    var m = textMap(), at = indexOfPoint(range.startContainer, range.startOffset);
    if (at < 0 || m.text.substr(at, text.length) !== text) {
      // Selection started on collapsed whitespace: use the nearest real occurrence.
      var best = -1, from = 0, i;
      while ((i = m.text.indexOf(text, from)) >= 0) { if (best < 0 || Math.abs(i - at) < Math.abs(best - at)) best = i; from = i + 1; }
      if (best < 0) return { text: text, occurrence: 0 };
      at = best;
    }
    return { text: text, occurrence: occurrencesBefore(text, at) };
  }

  // ---------------------------------------------------------------------------------------------
  // Pages

  // The last screen is often only partly filled, which would stop scrolling short of a whole page; an
  // invisible marker at the end of the last page keeps every page exactly one screen wide.
  var endMarker = null;
  function measure() {
    W = window.innerWidth;
    if (endMarker) endMarker.style.left = '0px';
    count = Math.max(1, Math.ceil((scroller().scrollWidth - 2) / W));
    if (!endMarker) {
      endMarker = document.createElement('div');
      endMarker.setAttribute('aria-hidden', 'true');
      endMarker.style.cssText = 'position:absolute;top:0;width:1px;height:1px;pointer-events:none;visibility:hidden';
      root.appendChild(endMarker);
    }
    endMarker.style.left = (count * W - 1) + 'px';
    marks.bookmarkPages = marks.bookmarkRanges.map(function (b) { return { id: b.id, page: pageOfRange(b.range) }; });
  }

  function pageOfX(x) { return Math.max(0, Math.min(count - 1, Math.floor((x + scroller().scrollLeft + 1) / W))); }
  function pageOf(el) { return el ? pageOfX(el.getBoundingClientRect().left) : 0; }
  function pageOfRange(r) {
    if (!r) return 0;
    var rects = r.getClientRects();
    var rect = rects.length ? rects[0] : r.getBoundingClientRect();
    return pageOfX(rect.left);
  }

  // First text on the current page, as an anchor for saving the reading position.
  function pageAnchor() {
    var padX = cssNumber('--pad-x', 20), padTop = cssNumber('--pad-top', 40), bottom = window.innerHeight - cssNumber('--pad-bottom', 40);
    for (var y = padTop + 2; y < bottom; y += 18) {
      for (var x = padX + 2; x < Math.min(W - padX, padX + 160); x += 40) {
        var pos = document.caretRangeFromPoint(x, y);
        if (!pos || pos.startContainer.nodeType !== 3) continue;
        var i = indexOfPoint(pos.startContainer, pos.startOffset);
        var m = textMap();
        if (i < 0 || i >= m.text.length) continue;
        while (i > 0 && m.text.charAt(i - 1) !== ' ' && i > indexOfPoint(pos.startContainer, 0)) i--;
        var text = m.text.substr(i, 48).replace(/\s+\S*$/, '').trim() || m.text.substr(i, 48).trim();
        if (text.length < 4) continue;
        return { text: text, occurrence: occurrencesBefore(text, i) };
      }
    }
    return null;
  }

  function currentBookmark() {
    for (var i = 0; i < marks.bookmarkPages.length; i++) if (marks.bookmarkPages[i].page === page) return marks.bookmarkPages[i].id;
    return 0;
  }

  function report() {
    var anchor = pageAnchor();
    A.onPage(page, count, anchor ? JSON.stringify(anchor) : '', currentBookmark());
  }

  function show(p) {
    page = Math.max(0, Math.min(count - 1, p));
    scroller().scrollLeft = page * W;
    report();
  }

  function next() { if (page < count - 1) show(page + 1); else A.nextChapter(); }
  function prev() { if (page > 0) show(page - 1); else A.prevChapter(); }

  // ---------------------------------------------------------------------------------------------
  // Highlights and bookmarks. Highlights use the CSS Custom Highlight API, so the book's DOM is untouched.

  var marks = { highlights: [], bookmarkRanges: [], bookmarkPages: [] };
  var hasHighlightApi = typeof CSS !== 'undefined' && CSS.highlights && typeof Highlight !== 'undefined';
  var colors = ['yellow', 'green', 'blue', 'pink'];

  function setMarks(data) {
    data = data || {};
    marks.highlights = (data.highlights || []).map(function (h) { return { id: h.id, color: h.color, note: h.note, range: locate(h) }; })
      .filter(function (h) { return h.range; });
    marks.bookmarkRanges = (data.bookmarks || []).map(function (b) { return { id: b.id, range: locate(b) }; })
      .filter(function (b) { return b.range; });
    if (hasHighlightApi) {
      colors.forEach(function (c) {
        var hl = new Highlight();
        marks.highlights.forEach(function (h) { if ((h.color || 'yellow') === c) hl.add(h.range); });
        CSS.highlights.set('reader-' + c, hl);
      });
      var notes = new Highlight();
      marks.highlights.forEach(function (h) { if (h.note) notes.add(h.range); });
      CSS.highlights.set('reader-note', notes);
    }
    marks.bookmarkPages = marks.bookmarkRanges.map(function (b) { return { id: b.id, page: pageOfRange(b.range) }; });
  }

  function flash(range) {
    if (!hasHighlightApi || !range) return;
    CSS.highlights.set('reader-flash', new Highlight(range));
    setTimeout(function () { CSS.highlights.delete('reader-flash'); }, 2500);
  }

  function highlightAt(x, y) {
    var pos = document.caretRangeFromPoint(x, y);
    if (!pos) return null;
    for (var i = 0; i < marks.highlights.length; i++) {
      var r = marks.highlights[i].range;
      try {
        if (r.isPointInRange(pos.startContainer, pos.startOffset) && r.comparePoint(pos.startContainer, pos.startOffset) === 0) {
          // A caret just after the last character is still "in" the range; require the tap to be over its text.
          var rects = r.getClientRects();
          for (var k = 0; k < rects.length; k++) {
            var b = rects[k];
            if (x >= b.left - 2 && x <= b.right + 2 && y >= b.top - 2 && y <= b.bottom + 2) return marks.highlights[i];
          }
        }
      } catch (err) { /* range from another document state */ }
    }
    return null;
  }

  // ---------------------------------------------------------------------------------------------
  // Start-up: wait for images and fonts so page positions are final, then go to the requested spot.

  function start() {
    if (ready) return;
    ready = true;
    setMarks(init.marks);
    measure();
    var p = 0, target = init.target || null, range = null;
    if (init.atEnd) p = count - 1;
    else if (target && target.id) p = pageOf(document.getElementById(target.id));
    else if (target && target.text && (range = locate(target))) p = pageOfRange(range);
    else if (init.position && init.position.text && (range = locate(init.position))) p = pageOfRange(range);
    else if (init.position && init.position.fraction) p = Math.floor(init.position.fraction * count + 0.0001);
    root.style.visibility = 'visible';
    show(p);
    if (target && target.flash && range) flash(range);
  }

  function whenLaidOut() {
    var fonts = document.fonts && document.fonts.ready ? document.fonts.ready : Promise.resolve();
    fonts.then(function () { requestAnimationFrame(start); });
  }
  if (document.readyState === 'complete') whenLaidOut(); else window.addEventListener('load', whenLaidOut);
  setTimeout(start, 2500); // never leave the page hidden if an image hangs

  window.addEventListener('resize', function () {
    if (!ready) return;
    var anchor = pageAnchor(), fraction = page / count;
    measure();
    var r = anchor && locate(anchor);
    show(r ? pageOfRange(r) : Math.floor(fraction * count + 0.0001));
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

  // ---------------------------------------------------------------------------------------------
  // Taps: left third = back, right third = forward, middle = toolbar; taps on a highlight open its note.
  // Horizontal swipes turn pages.

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
    var hl = highlightAt(e.clientX, e.clientY);
    if (hl) { A.onHighlightTap(hl.id); return; }
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

  // ---------------------------------------------------------------------------------------------
  // Selection. The app asks for it after its menu closes, which clears the selection, so keep the last one.

  var lastRange = null;
  function blockOf(node) {
    var el = node.nodeType === 3 ? node.parentElement : node;
    return (el && el.closest && el.closest('p,li,blockquote,dd,dt,td,th,h1,h2,h3,h4,h5,h6,div,section')) || el || document.body;
  }
  function excerpt(text, around, max) {
    if (text.length <= max) return text;
    var i = Math.max(0, text.indexOf(around));
    var from = Math.max(0, i - Math.floor((max - around.length) / 2));
    var to = Math.min(text.length, from + max);
    return (from > 0 ? '… ' : '') + text.slice(from, to).trim() + (to < text.length ? ' …' : '');
  }
  // The sentence(s) of `block` that contain `text`.
  function sentenceOf(block, text) {
    var i = block.indexOf(text);
    if (i < 0) return '';
    var start = i;
    while (start > 0 && !/[.!?…]["»”’)]? $/.test(block.slice(Math.max(0, start - 3), start))) start--;
    var end = i + text.length;
    if (!/[.!?…]$/.test(text)) {
      while (end < block.length && !/[.!?…]/.test(block.charAt(end))) end++;
      if (end < block.length) end++;
    }
    while (end < block.length && /["»”’)]/.test(block.charAt(end))) end++;
    return block.slice(start, end).trim();
  }
  document.addEventListener('selectionchange', function () {
    var s = window.getSelection();
    if (!s || s.isCollapsed || !s.rangeCount) return;
    if (!s.toString().trim()) return;
    lastRange = s.getRangeAt(0).cloneRange();
  });

  function selectionInfo() {
    var r = lastRange;
    lastRange = null;
    if (!r) return { text: '', context: '', sentence: '', anchor: null };
    var text = normalize(r.toString()).trim();
    var block = blockOf(r.commonAncestorContainer);
    var blockText = normalize(block.innerText || block.textContent || '');
    var context = excerpt(blockText, text, 1500);
    return {
      text: text,
      context: context === text ? '' : context,
      sentence: sentenceOf(blockText, text) || text,
      anchor: anchorFor(r)
    };
  }

  function pageText() {
    var pad = cssNumber('--pad-x', 20), top = cssNumber('--pad-top', 40), bottom = cssNumber('--pad-bottom', 40);
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
    info: function () { return JSON.stringify({ page: page, count: count, bookmark: currentBookmark() }); },
    selectionInfo: function () { return JSON.stringify(selectionInfo()); },
    clearSelection: function () { var s = window.getSelection(); if (s) s.removeAllRanges(); },
    pageText: pageText,
    pageAnchor: function () { var a = pageAnchor(); return a ? JSON.stringify(a) : ''; },
    setMarks: function (json) { setMarks(typeof json === 'string' ? JSON.parse(json) : json); report(); },
    goTo: function (json) {
      var a = typeof json === 'string' ? JSON.parse(json) : json;
      var r = locate(a);
      if (r) { show(pageOfRange(r)); if (a.flash) flash(r); }
      return !!r;
    },
    // For tests
    locate: function (json) { var r = locate(typeof json === 'string' ? JSON.parse(json) : json); return r ? r.toString() : null; }
  };
})();
