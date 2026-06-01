(function () {
  'use strict';

  if (window.plugin_alldebrid_test) return;
  window.plugin_alldebrid_test = true;

  var POLL_MS = 1000;
  var buttonAdded = false;
  var pollTimer = null;
  var hooksInstalled = false;

  function getActiveMovie() {
    var activity = Lampa.Activity.active();
    if (!activity) return null;
    return activity.card || activity.movie || null;
  }

  function logMovieContext(label) {
    var movie = getActiveMovie();
    var activity = Lampa.Activity.active();

    var title = '';
    var tmdbId = null;
    var searchQuery = '';

    if (movie) {
      title = movie.title || movie.name || '';
      tmdbId = movie.id != null ? movie.id : null;
    }

    if (activity) {
      searchQuery = activity.search || activity.search_one || activity.search_two || '';
    }

    console.log('[AllDebrid] ' + label);
    console.log('[AllDebrid]   movie title:', title);
    console.log('[AllDebrid]   TMDB id:', tmdbId);
    console.log('[AllDebrid]   search query:', searchQuery);
    console.log('[AllDebrid]   activity:', activity);
    console.log('[AllDebrid]   movie:', movie);
  }

  function logCall(label, fnName, args, extra) {
    console.group('[AllDebrid] ' + label + '.' + fnName);

    for (var i = 0; i < args.length; i++) {
      console.log('arg[' + i + ']:', args[i]);
    }

    if (extra !== undefined) console.log('extra:', extra);

    logMovieContext(fnName);

    console.groupEnd();
  }

  function wrapMethod(obj, name, label) {
    if (!obj || typeof obj[name] !== 'function') return false;

    var original = obj[name];
    obj[name] = function () {
      var args = [].slice.call(arguments);
      logCall(label, name, args);
      return original.apply(this, arguments);
    };

    console.log('[AllDebrid] hooked: ' + label + '.' + name);
    return true;
  }

  function installTorrentHooks() {
    if (hooksInstalled) return;
    hooksInstalled = true;

    console.log('[AllDebrid] installing torrent intercept hooks');

    if (Lampa.Parser) {
      wrapMethod(Lampa.Parser, 'init', 'Parser');
      wrapMethod(Lampa.Parser, 'get', 'Parser');
      wrapMethod(Lampa.Parser, 'jackett', 'Parser');
      wrapMethod(Lampa.Parser, 'clear', 'Parser');
    } else {
      console.log('[AllDebrid] Lampa.Parser not found');
    }

    if (Lampa.Torrent) {
      wrapMethod(Lampa.Torrent, 'start', 'Torrent');
      wrapMethod(Lampa.Torrent, 'open', 'Torrent');
    } else {
      console.log('[AllDebrid] Lampa.Torrent not found');
    }

    Lampa.Listener.follow('torrent', function (e) {
      console.log('[AllDebrid] Listener torrent event:', e);
      logMovieContext('torrent listener / ' + (e && e.type ? e.type : 'unknown'));
    });

    Lampa.Listener.follow('activity', function (e) {
      var active = Lampa.Activity.active();
      var isTorrent =
        (e && e.component === 'torrent') ||
        (active && active.component === 'torrent');

      if (!isTorrent) return;

      console.log('[AllDebrid] activity (torrent):', e);
      logMovieContext('activity torrent / ' + (e && e.type ? e.type : 'unknown'));
    });
  }

  function injectButton() {
    if (buttonAdded) return;
    if ($('.button--alldebrid').length) {
      buttonAdded = true;
      return;
    }

    var mount = $('.full-start-new__buttons');
    if (!mount.length) return;

    mount.append(
      '<div class="full-start__button selector button--alldebrid">' +
        '<span>AllDebrid TEST</span>' +
      '</div>'
    );

    $('.button--alldebrid').on('hover:enter click', function () {
      Lampa.Noty.show('Button works');
    });

    buttonAdded = true;
    console.log('[AllDebrid] BUTTON ADDED');

    if (pollTimer) {
      clearInterval(pollTimer);
      pollTimer = null;
    }
  }

  function startPolling() {
    injectButton();
    pollTimer = setInterval(injectButton, POLL_MS);
  }

  function init() {
    console.log('[AllDebrid] plugin ready');
    installTorrentHooks();
    startPolling();
  }

  if (window.appready) {
    init();
  } else {
    Lampa.Listener.follow('app', function (e) {
      if (e.type === 'ready') init();
    });
  }
})();
