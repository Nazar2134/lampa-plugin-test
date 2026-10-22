// Runs inside every chapter page. Talks to the app through the `Android` JavaScript interface.
(function () {
  'use strict';
  var init = window.READER_INIT || {};
  var A = window.Android || {
    onPage: function () {}, onTap: function () {}, nextChapter: function () {}, prevChapter: function () {},
    openLink: function () {}, openExternal: function () {}, onHighlightTap: function () {}, onSelectionChanged: function () {},
    onWordTap: function () {}, onWordStats: function () {}
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

  // First text node at or inside `n`, else the first one after it in document order.
  function firstTextFrom(n) {
    if (!n) return null;
    if (n.nodeType === 3) return n;
    var t = document.createTreeWalker(n, NodeFilter.SHOW_TEXT).nextNode();
    return t || firstTextAfter(n);
  }
  function firstTextAfter(n) {
    while (n && !n.nextSibling) n = n.parentNode;
    return n ? firstTextFrom(n.nextSibling) : null;
  }

  // Map index of a DOM boundary point (the index of the first character at or after it).
  function indexOfPoint(node, offset) {
    var m = textMap();
    if (node.nodeType !== 3) {
      var t = offset < node.childNodes.length ? firstTextFrom(node.childNodes[offset]) : firstTextAfter(node);
      // Skip text the map leaves out (scripts, styles).
      while (t && m.first.get(t) === undefined) t = firstTextAfter(t);
      if (!t) return m.text.length;
      node = t;
      offset = 0;
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
    reportWordStats();
  }

  // The page's scroll position. Anything else that moves it (selection handles dragged to the edge,
  // the browser scrolling a caret into view) is undone straight away, so the page never drifts.
  var lockedX = 0, animation = 0, dragging = false;
  var reduceMotion = !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);

  function setScroll(x) { lockedX = x; scroller().scrollLeft = x; }
  function clampPage(p) { return Math.max(0, Math.min(count - 1, p)); }
  function cancelAnimation() { if (animation) cancelAnimationFrame(animation); animation = 0; }

  // Jumps straight to a page (opening a chapter, links, search results).
  function show(p) {
    cancelAnimation();
    page = clampPage(p);
    setScroll(page * W);
    report();
  }

  // Slides to a page with an ease-out curve (page turns).
  function slideTo(p, duration) {
    cancelAnimation();
    page = clampPage(p);
    var from = scroller().scrollLeft, to = page * W;
    if (reduceMotion || !duration || Math.abs(from - to) < 1) { setScroll(to); report(); return; }
    var t0 = 0;
    function step(now) {
      if (!t0) t0 = now;
      var k = Math.min(1, (now - t0) / duration);
      setScroll(from + (to - from) * (1 - Math.pow(1 - k, 3)));
      if (k < 1) { animation = requestAnimationFrame(step); return; }
      animation = 0;
      setScroll(to);
      report();
    }
    animation = requestAnimationFrame(step);
  }

  function next() { if (page < count - 1) slideTo(page + 1, 260); else A.nextChapter(); }
  function prev() { if (page > 0) slideTo(page - 1, 260); else A.prevChapter(); }

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
    applyLearning();
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
    if (init.resume && init.position && range) showResumeMarker(range, init.resume.label || 'You stopped here');
  }

  // "You stopped here", shown when a book is reopened: a bar in the margin beside the first line read, a
  // label in the page's top margin (never over text), and a brief tint on the first words.
  var resumeMarker = [];
  function showResumeMarker(range, label) {
    var rects = range.getClientRects();
    if (!rects.length) return;
    var r = rects[0], padX = cssNumber('--pad-x', 20), padTop = cssNumber('--pad-top', 40), x0 = scroller().scrollLeft;
    var lineLeft = r.left, boxes = blockOf(range.startContainer).getClientRects();
    for (var i = 0; i < boxes.length; i++) {
      if (boxes[i].top <= r.top + 1 && boxes[i].bottom >= r.bottom - 1) { lineLeft = boxes[i].left; break; }
    }
    var bar = document.createElement('div');
    bar.className = 'reader-resume';
    bar.style.left = (lineLeft + x0 - Math.min(padX - 2, 10)) + 'px';
    bar.style.top = r.top + 'px';
    bar.style.height = r.height + 'px';
    var tag = document.createElement('div');
    tag.className = 'reader-resume-label';
    tag.setAttribute('role', 'status');
    tag.textContent = label;
    tag.style.left = (lineLeft + x0) + 'px';
    tag.style.top = Math.max(4, padTop - 30) + 'px';
    root.appendChild(bar);
    root.appendChild(tag);
    resumeMarker = [bar, tag];
    // Tint the first few words of the line.
    var m = textMap(), at = indexOfPoint(range.startContainer, range.startOffset);
    var words = rangeAt(at, Math.min(m.text.length - at, (m.text.slice(at, at + 60).match(/^(\S+\s+){0,3}\S+/) || [''])[0].length));
    if (words) flash(words);
    setTimeout(hideResumeMarker, 6000);
  }
  function hideResumeMarker() {
    var parts = resumeMarker;
    resumeMarker = [];
    parts.forEach(function (m) { m.classList.add('hide'); setTimeout(function () { m.remove(); }, 600); });
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

  window.addEventListener('scroll', function () {
    if (!ready || animation || dragging) return;
    if (Math.abs(scroller().scrollLeft - lockedX) > 0.5) scroller().scrollLeft = lockedX;
  }, { passive: true });

  function hasSelection() {
    var s = window.getSelection();
    return !!s && !s.isCollapsed && s.toString().trim().length > 0;
  }

  // ---------------------------------------------------------------------------------------------
  // Gestures. The page follows a horizontal drag; releasing past ~a fifth of the screen (or flicking)
  // turns it, otherwise it slides back. Quick taps on the outer quarters turn pages, the middle shows the
  // toolbar, a tap on a highlight opens its note. Presses longer than a tap, drags that begin after a
  // long press, and anything while text is selected never turn the page.

  var TAP_MS = 300, LONG_PRESS_MS = 350, SLOP = 10;
  var gesture = null, lastGesture = null;

  function setOverscroll(px) {
    document.body.style.transition = '';
    document.body.style.transform = px ? 'translateX(' + px + 'px)' : '';
  }
  function releaseOverscroll() {
    if (!document.body.style.transform) return;
    document.body.style.transition = reduceMotion ? '' : 'transform 180ms ease-out';
    document.body.style.transform = '';
  }

  document.addEventListener('touchstart', function (e) {
    if (e.touches.length !== 1) { gesture = null; return; }
    var t = e.touches[0], now = Date.now();
    if (animation) { cancelAnimation(); setScroll(page * W); report(); }
    gesture = { x: t.clientX, y: t.clientY, t: now, lastX: t.clientX, lastT: now, vx: 0, moved: false, drag: false, selection: hasSelection() };
  }, { passive: true });

  document.addEventListener('touchmove', function (e) {
    var g = gesture;
    if (!g || e.touches.length !== 1) return;
    var t = e.touches[0], now = Date.now(), dx = t.clientX - g.x, dy = t.clientY - g.y;
    if (!g.moved && Math.abs(dx) + Math.abs(dy) > SLOP) {
      g.moved = true;
      g.drag = !g.selection && !hasSelection() && now - g.t < LONG_PRESS_MS && Math.abs(dx) > Math.abs(dy) * 1.2;
      if (g.drag) dragging = true;
    }
    if (!g.drag) return;
    var dt = now - g.lastT;
    if (dt > 0) g.vx = 0.6 * ((t.clientX - g.lastX) / dt) + 0.4 * g.vx;
    g.lastX = t.clientX;
    g.lastT = now;
    var x = page * W - dx, max = (count - 1) * W, over = 0;
    // Past the chapter's first or last page the content stretches a little instead.
    if (x < 0) { over = -x * 0.3; x = 0; } else if (x > max) { over = -(x - max) * 0.3; x = max; }
    setScroll(x);
    setOverscroll(over);
  }, { passive: true });

  function endDrag(g, dx) {
    dragging = false;
    releaseOverscroll();
    var flick = Math.abs(g.vx) > 0.35 && Math.abs(dx) > 24 && (g.vx < 0) === (dx < 0) && Date.now() - g.lastT < 120;
    if (Math.abs(dx) > W * 0.2 || flick) {
      if (dx < 0) {
        if (page < count - 1) { slideTo(page + 1, 200); return; }
        slideTo(page, 160); A.nextChapter(); return;
      }
      if (page > 0) { slideTo(page - 1, 200); return; }
      slideTo(page, 160); A.prevChapter(); return;
    }
    slideTo(page, 180);
  }

  document.addEventListener('touchend', function (e) {
    var g = gesture;
    gesture = null;
    if (!g) return;
    g.duration = Date.now() - g.t;
    lastGesture = g;
    if (g.drag) endDrag(g, e.changedTouches[0].clientX - g.x);
  }, { passive: true });

  document.addEventListener('touchcancel', function () {
    var g = gesture;
    gesture = null;
    lastGesture = { cancelled: true };
    if (g && g.drag) { dragging = false; releaseOverscroll(); slideTo(page, 180); }
  }, { passive: true });

  document.addEventListener('click', function (e) {
    var g = lastGesture;
    lastGesture = null;
    if (g && (g.cancelled || g.drag || g.moved)) return;
    var a = e.target.closest ? e.target.closest('a[href]') : null;
    if (a) { e.preventDefault(); followLink(a.getAttribute('href')); return; }
    if (g && g.selection) return; // this tap only dismissed a selection
    if (g && g.duration > TAP_MS) return; // a press, not a tap (e.g. a long press that didn't select)
    var hl = highlightAt(e.clientX, e.clientY);
    if (hl) { A.onHighlightTap(hl.id); return; }
    if (learning && learning.enabled) {
      var w = wordAt(e.clientX, e.clientY);
      if (w) { tapWord(w); return; }
    }
    var x = (e.clientX % W) / W;
    if (x < 0.25) prev(); else if (x > 0.75) next(); else A.onTap();
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
  var selectionActive = false, selectionTimer = 0;
  document.addEventListener('selectionchange', function () {
    var s = window.getSelection();
    var active = !!s && !s.isCollapsed && s.rangeCount > 0 && s.toString().trim().length > 0;
    if (active) lastRange = s.getRangeAt(0).cloneRange();
    clearTimeout(selectionTimer);
    selectionTimer = setTimeout(function () { reportSelection(active); }, 120);
  });

  // Tells the app whether text is selected and where (so its selection bar can avoid covering it).
  function reportSelection(active) {
    var top = 0, bottom = 0;
    if (active && lastRange) {
      var rects = lastRange.getClientRects();
      if (rects.length) { top = rects[0].top; bottom = rects[rects.length - 1].bottom; }
    }
    if (active || selectionActive) A.onSelectionChanged(active, Math.round(top), Math.round(bottom));
    selectionActive = active;
  }

  function isWordChar(c) { return !!c && /[\p{L}\p{N}'’\-]/u.test(c); }

  function selectIndices(a, b) {
    var m = textMap();
    while (a < b && m.text.charAt(a) === ' ') a++;
    while (b > a && m.text.charAt(b - 1) === ' ') b--;
    var r = rangeAt(a, b - a);
    if (!r) return '';
    var s = window.getSelection();
    s.removeAllRanges();
    s.addRange(r);
    lastRange = r.cloneRange();
    reportSelection(true);
    return normalize(r.toString());
  }

  function selectionIndices() {
    var s = window.getSelection();
    var r = s && s.rangeCount && !s.isCollapsed ? s.getRangeAt(0) : lastRange;
    if (!r) return null;
    var a = indexOfPoint(r.startContainer, r.startOffset), b = indexOfPoint(r.endContainer, r.endOffset);
    return a < 0 || b < 0 ? null : { a: a, b: Math.max(a, b), range: r };
  }

  // Start and end of the block element (paragraph, heading, list item) around a range, as map indices.
  function blockIndices(r) {
    var block = blockOf(r.startContainer), all = document.createRange();
    all.selectNodeContents(block);
    return { a: indexOfPoint(all.startContainer, all.startOffset), b: indexOfPoint(all.endContainer, all.endOffset) };
  }

  function expandSelection(scope) {
    var sel = selectionIndices();
    if (!sel) return '';
    var m = textMap(), t = m.text, a = sel.a, b = sel.b, blk = blockIndices(sel.range);
    if (scope === 'word') {
      while (a > 0 && isWordChar(t.charAt(a - 1))) a--;
      while (b < t.length && isWordChar(t.charAt(b))) b++;
    } else if (scope === 'sentence') {
      while (a > blk.a && !/[.!?…]["»”’)]? $/.test(t.slice(Math.max(blk.a, a - 3), a))) a--;
      while (b < blk.b && !(/[.!?…]/.test(t.charAt(b - 1)) && (b === blk.b || /[ "»”’)]/.test(t.charAt(b))))) b++;
      while (b < blk.b && /["»”’)]/.test(t.charAt(b))) b++;
    } else if (scope === 'paragraph') {
      a = blk.a;
      b = blk.b;
    }
    return selectIndices(a, b);
  }

  // Moves one edge of the selection by a word: edge 'start' or 'end', dir -1 (left) or +1 (right).
  function nudgeSelection(edge, dir) {
    var sel = selectionIndices();
    if (!sel) return '';
    var t = textMap().text, a = sel.a, b = sel.b;
    function wordLeft(i) { while (i > 0 && !isWordChar(t.charAt(i - 1))) i--; while (i > 0 && isWordChar(t.charAt(i - 1))) i--; return i; }
    function wordRight(i) { while (i < t.length && !isWordChar(t.charAt(i))) i++; while (i < t.length && isWordChar(t.charAt(i))) i++; return i; }
    if (edge === 'start') a = dir < 0 ? wordLeft(a) : Math.min(wordRight(a), b - 1);
    else b = dir > 0 ? wordRight(b) : Math.max(wordLeft(b), a + 1);
    // Skip punctuation and spaces at the inner edge after shrinking.
    while (a < b && !isWordChar(t.charAt(a)) && edge === 'start' && dir > 0) a++;
    return selectIndices(a, b);
  }

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


  // ---------------------------------------------------------------------------------------------
  // Learning mode. Words are tokens of the text map; their statuses come from the app (1 = learning,
  // 2 = known, missing = new). Words being learnt are tinted, new words optionally underlined, a tap on
  // a word asks the app for its meaning, and each page reports how many new words it has.

  var learning = init.learning || null;
  var tokens = null;
  var WORD = /[\p{L}][\p{L}\p{M}'’\-]*/gu;

  function formOf(w) { return w.replace(/^['’\-]+|['’\-]+$/g, '').toLowerCase(); }

  function wordTokens() {
    if (tokens) return tokens;
    var t = textMap().text, m;
    tokens = [];
    WORD.lastIndex = 0;
    while ((m = WORD.exec(t))) {
      var f = formOf(m[0]);
      if (f) tokens.push({ s: m.index, e: m.index + m[0].length, f: f });
    }
    return tokens;
  }

  // Index of the first token starting at or after text index i.
  function firstTokenFrom(i) {
    var tk = wordTokens(), lo = 0, hi = tk.length;
    while (lo < hi) { var mid = (lo + hi) >> 1; if (tk[mid].s < i) lo = mid + 1; else hi = mid; }
    return lo;
  }

  function tokenAt(i) {
    var tk = wordTokens(), k = firstTokenFrom(i + 1) - 1;
    return k >= 0 && tk[k].s <= i && i < tk[k].e ? tk[k] : null;
  }

  function statusOf(f) { return (learning && learning.statuses && learning.statuses[f]) || 0; }

  function applyLearning() {
    if (!hasHighlightApi) return;
    var learn = new Highlight(), fresh = new Highlight();
    if (learning && learning.enabled) {
      wordTokens().forEach(function (tk) {
        var st = statusOf(tk.f);
        if (st === 1) learn.add(rangeAt(tk.s, tk.e - tk.s));
        else if (st === 0 && learning.underlineNew) fresh.add(rangeAt(tk.s, tk.e - tk.s));
      });
    }
    CSS.highlights.set('reader-learning', learn);
    CSS.highlights.set('reader-new', fresh);
  }

  // Text indices [start, end) shown on the current page.
  function pageSpan() {
    var m = textMap(), pad = cssNumber('--pad-x', 20), top = cssNumber('--pad-top', 40), bottom = window.innerHeight - cssNumber('--pad-bottom', 40);
    var start = -1, end = -1, y, pos;
    for (y = top + 2; y < bottom && start < 0; y += 16) {
      pos = document.caretRangeFromPoint(pad + 2, y);
      if (pos && pos.startContainer.nodeType === 3) start = indexOfPoint(pos.startContainer, pos.startOffset);
    }
    for (y = bottom - 2; y > top && end < 0; y -= 16) {
      pos = document.caretRangeFromPoint(W - pad - 2, y);
      if (pos && pos.startContainer.nodeType === 3) end = indexOfPoint(pos.startContainer, pos.startOffset);
    }
    if (start < 0) start = 0;
    if (end < start || page === count - 1) end = Math.max(end, page === count - 1 ? m.text.length : start);
    return [start, end];
  }

  function pageTokens() {
    var span = pageSpan(), tk = wordTokens(), out = [];
    for (var k = firstTokenFrom(span[0]); k < tk.length && tk[k].s < span[1]; k++) out.push(tk[k]);
    return out;
  }

  function reportWordStats() {
    if (!learning || !learning.enabled || !ready) return;
    var total = 0, fresh = 0, learn = 0;
    pageTokens().forEach(function (tk) {
      total++;
      var st = statusOf(tk.f);
      if (st === 0) fresh++; else if (st === 1) learn++;
    });
    A.onWordStats(total, fresh, learn);
  }

  function newWordsOnPage() {
    var seen = {}, out = [];
    pageTokens().forEach(function (tk) { if (statusOf(tk.f) === 0 && !seen[tk.f]) { seen[tk.f] = 1; out.push(tk.f); } });
    return out;
  }

  // Sentence bounds (text indices) around [a, b), within its block element.
  function sentenceBounds(a, b, blk) {
    var t = textMap().text;
    while (a > blk.a && !/[.!?…]["»”’)]? $/.test(t.slice(Math.max(blk.a, a - 3), a))) a--;
    while (b < blk.b && !(/[.!?…]/.test(t.charAt(b - 1)) && (b === blk.b || /[ "»”’)]/.test(t.charAt(b))))) b++;
    while (b < blk.b && /["»”’)]/.test(t.charAt(b))) b++;
    return [a, b];
  }

  function wordAt(x, y) {
    var pos = document.caretRangeFromPoint(x, y);
    if (!pos || pos.startContainer.nodeType !== 3) return null;
    var i = indexOfPoint(pos.startContainer, pos.startOffset);
    var tk = tokenAt(i) || tokenAt(i - 1);
    if (!tk) return null;
    var r = rangeAt(tk.s, tk.e - tk.s), rects = r.getClientRects();
    for (var k = 0; k < rects.length; k++) {
      var b = rects[k];
      if (x >= b.left - 3 && x <= b.right + 3 && y >= b.top - 3 && y <= b.bottom + 3) return { token: tk, range: r, rect: b };
    }
    return null;
  }

  function tapWord(w) {
    var t = textMap().text, word = t.slice(w.token.s, w.token.e);
    var sb = sentenceBounds(w.token.s, w.token.e, blockIndices(w.range));
    if (hasHighlightApi) CSS.highlights.set('reader-tap', new Highlight(w.range));
    A.onWordTap(JSON.stringify({
      word: word, form: w.token.f, sentence: t.slice(sb[0], sb[1]).trim(),
      top: Math.round(w.rect.top), bottom: Math.round(w.rect.bottom),
      occurrence: occurrencesBefore(word, w.token.s)
    }));
  }

  // ---------------------------------------------------------------------------------------------
  // Reading aloud: sentences from the top of the current page to the end of the chapter; the one being
  // spoken is highlighted and its page shown.

  var spokenSentences = [];

  function sentencesFromPage() {
    var t = textMap().text, start = pageSpan()[0], out = [];
    var blocks = Array.prototype.filter.call(document.body.querySelectorAll('p,h1,h2,h3,h4,h5,h6,li,dd,dt,figcaption,pre,td,blockquote,div'), function (el) {
      return !el.querySelector('p,h1,h2,h3,h4,h5,h6,li,dd,dt,figcaption,pre,td,blockquote,div');
    });
    blocks.forEach(function (el) {
      var all = document.createRange();
      all.selectNodeContents(el);
      var blk = { a: indexOfPoint(all.startContainer, all.startOffset), b: indexOfPoint(all.endContainer, all.endOffset) };
      var a = blk.a;
      while (a < blk.b && out.length < 600) {
        while (a < blk.b && t.charAt(a) === ' ') a++;
        if (a >= blk.b) break;
        var b = sentenceBounds(a, a + 1, blk)[1];
        if (b <= a) b = blk.b;
        if (b > start) out.push({ s: Math.max(a, start), e: b, text: t.slice(Math.max(a, start), b).trim() });
        a = b;
      }
    });
    out = out.filter(function (x) { return x.text && /[\p{L}\p{N}]/u.test(x.text); });
    spokenSentences = out;
    return out.map(function (x) { return x.text; });
  }

  function speaking(i) {
    var x = spokenSentences[i];
    if (!x || !hasHighlightApi) return;
    var r = rangeAt(x.s, x.e - x.s);
    CSS.highlights.set('reader-speaking', new Highlight(r));
    var p = pageOfRange(r);
    if (p !== page) slideTo(p, 260);
  }

  window.reader = {
    next: next,
    prev: prev,
    info: function () { return JSON.stringify({ page: page, count: count, bookmark: currentBookmark() }); },
    selectionInfo: function () { return JSON.stringify(selectionInfo()); },
    clearSelection: function () { var s = window.getSelection(); if (s) s.removeAllRanges(); lastRange = null; reportSelection(false); },
    expandSelection: function (scope) { return expandSelection(scope); },
    nudgeSelection: function (edge, dir) { return nudgeSelection(edge, dir); },
    selectedText: function () { var s = window.getSelection(); return s ? normalize(s.toString()).trim() : ''; },
    hideResumeMarker: hideResumeMarker,
    setLearning: function (json) { learning = typeof json === 'string' ? JSON.parse(json) : json; applyLearning(); reportWordStats(); },
    setWordStatuses: function (json) {
      var delta = typeof json === 'string' ? JSON.parse(json) : json;
      if (!learning) return;
      learning.statuses = learning.statuses || {};
      Object.keys(delta).forEach(function (f) { if (delta[f]) learning.statuses[f] = delta[f]; else delete learning.statuses[f]; });
      applyLearning();
      reportWordStats();
    },
    newWordsOnPage: function () { return JSON.stringify(newWordsOnPage()); },
    clearTap: function () { if (hasHighlightApi) CSS.highlights.delete('reader-tap'); },
    sentencesFromPage: function () { return JSON.stringify(sentencesFromPage()); },
    speaking: speaking,
    stopSpeaking: function () { if (hasHighlightApi) CSS.highlights.delete('reader-speaking'); },
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
