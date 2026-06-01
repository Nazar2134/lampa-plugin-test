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

  function logCall(label, fnName, args) {
    console.group('[AllDebrid] ' + label + '.' + fnName);

    console.log('args.length =', args.length);

    for (var i = 0; i < args.length; i++) {
      console.log('arg[' + i + ']', args[i]);
    }

    console.trace();

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
      Object.getOwnPropertyNames(Lampa.Torrent).forEach(function (name) {
        if (typeof Lampa.Torrent[name] !== 'function') return;

        var original = Lampa.Torrent[name];

        Lampa.Torrent[name] = function () {
          console.group('[AllDebrid] Torrent.' + name);

          console.log('args.length', arguments.length);

          for (var i = 0; i < arguments.length; i++) {
            console.log('arg[' + i + ']', arguments[i]);
          }

          console.trace();

          console.groupEnd();

          return original.apply(this, arguments);
        };

        console.log('[AllDebrid] hooked: Torrent.' + name);
      });
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

  var APIBAY_PROBE_URL = 'https://apibay.org/q.php?q=matrix';

  function probeLampaRequestApis() {
    console.group('[AllDebrid] Lampa Request / Reguest probe');

    var reguest = typeof Lampa !== 'undefined' ? Lampa.Reguest : undefined;
    var request = typeof Lampa !== 'undefined' ? Lampa.Request : undefined;

    console.log('Lampa.Reguest typeof:', typeof reguest);
    console.log('Lampa.Request typeof:', typeof request);
    console.log('Lampa.Reguest === Lampa.Request:', reguest === request);

    console.log('Lampa.Reguest.native typeof:', typeof (reguest && reguest.native));
    console.log(
      'Lampa.Reguest.prototype.native typeof:',
      typeof (reguest && reguest.prototype && reguest.prototype.native)
    );

    var instance = null;
    var instanceNative = null;

    if (typeof reguest === 'function') {
      try {
        instance = new reguest();
        instanceNative = instance && instance.native;
        console.log('new Lampa.Reguest() OK, instance.native typeof:', typeof instanceNative);
      } catch (e) {
        console.warn('new Lampa.Reguest() failed:', e);
      }
    }

    function summarizeApibay(label, data, err) {
      var preview = '';
      var count = null;

      if (err) {
        console.log(label + ' — ERROR', err);
        return;
      }

      if (typeof data === 'string') {
        preview = data.slice(0, 200);
        try {
          var parsed = JSON.parse(data);
          count = Array.isArray(parsed) ? parsed.length : null;
        } catch (parseErr) {
          count = null;
        }
      } else if (Array.isArray(data)) {
        count = data.length;
        preview = JSON.stringify(data[0] || {}).slice(0, 200);
      } else {
        preview = String(data).slice(0, 200);
      }

      console.log(label + ' — OK', {
        type: typeof data,
        isArray: Array.isArray(data),
        count: count,
        preview: preview
      });
    }

    function probeFetch() {
      if (typeof window.fetch !== 'function') {
        console.log('fetch — not available');
        return;
      }

      window
        .fetch(APIBAY_PROBE_URL, { method: 'GET', mode: 'cors' })
        .then(function (response) {
          return response.text().then(function (text) {
            console.log('fetch — HTTP', response.status, 'type', response.type);
            if (!response.ok) {
              summarizeApibay('fetch', null, 'HTTP ' + response.status);
              return;
            }
            summarizeApibay('fetch', text);
          });
        })
        .catch(function (err) {
          summarizeApibay('fetch', null, err && (err.message || err));
        });
    }

    function probeReguestNative() {
      if (!instance || typeof instanceNative !== 'function') {
        console.log('Lampa.Reguest#native — not callable on instance');
        return;
      }

      instanceNative.call(
        instance,
        APIBAY_PROBE_URL,
        function (data) {
          summarizeApibay('Lampa.Reguest#native (instance)', data);
        },
        function (err) {
          summarizeApibay(
            'Lampa.Reguest#native (instance)',
            null,
            err && (err.message || err.decode_error || err.status || err)
          );
        }
      );
    }

    function probeReguestQuiet() {
      if (!instance || typeof instance.quiet !== 'function') {
        console.log('Lampa.Reguest#quiet — not available');
        return;
      }

      instance.quiet(
        APIBAY_PROBE_URL,
        function (data) {
          summarizeApibay('Lampa.Reguest#quiet (instance)', data);
        },
        function (err) {
          summarizeApibay(
            'Lampa.Reguest#quiet (instance)',
            null,
            err && (err.message || err.decode_error || err.status || err)
          );
        }
      );
    }

    if (typeof reguest === 'function' && typeof reguest.native === 'function') {
      try {
        reguest.native(
          APIBAY_PROBE_URL,
          function (data) {
            summarizeApibay('Lampa.Reguest.native (static)', data);
          },
          function (err) {
            summarizeApibay(
              'Lampa.Reguest.native (static)',
              null,
              err && (err.message || err.decode_error || err.status || err)
            );
          }
        );
      } catch (e) {
        console.log('Lampa.Reguest.native (static) — threw', e);
      }
    }

    probeFetch();
    probeReguestNative();
    probeReguestQuiet();

    console.log('Probe URL:', APIBAY_PROBE_URL);
    console.groupEnd();
  }

  function init() {
    console.log('[AllDebrid] plugin ready');
    installTorrentHooks();
    probeLampaRequestApis();
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
