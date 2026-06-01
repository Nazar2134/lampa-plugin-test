(function () {
  'use strict';

  if (window.plugin_alldebrid) return;
  window.plugin_alldebrid = true;

  var POLL_MS = 1000;
  var injectedFor = null;

  var MOCK_RESULTS = [
    { title: '1080p WEB-DL', subtitle: 'Cached · 4.2 GB' },
    { title: '720p BluRay', subtitle: 'Cached · 2.1 GB' },
    { title: '2160p REMUX', subtitle: 'Mock result' }
  ];

  function getMovie(activity) {
    return activity.card || activity.movie || null;
  }

  function extractMeta(movie) {
    if (!movie) {
      return {
        title: '',
        original_title: '',
        release_date: '',
        tmdb_id: null
      };
    }

    return {
      title: movie.title || movie.name || '',
      original_title: movie.original_title || movie.original_name || '',
      release_date: movie.release_date || movie.first_air_date || '',
      tmdb_id: movie.id != null ? movie.id : null
    };
  }

  function showMenu(meta) {
    var items = MOCK_RESULTS.map(function (row) {
      return {
        title: row.title,
        subtitle: row.subtitle,
        onSelect: function () {
          Lampa.Noty.show('Selected: ' + row.title);
        }
      };
    });

    items.unshift({
      title: meta.title || 'Unknown',
      subtitle: [meta.original_title, meta.release_date, meta.tmdb_id ? 'TMDB ' + meta.tmdb_id : '']
        .filter(Boolean)
        .join(' · '),
      separator: true
    });

    Lampa.Select.show({
      title: 'AllDebrid',
      items: items,
      onBack: function () {
        Lampa.Controller.toggle('full_start');
      },
      onSelect: function (el) {
        if (el.onSelect) el.onSelect(el);
      }
    });
  }

  function onButtonClick(activity) {
    var movie = getMovie(activity);
    var meta = extractMeta(movie);

    console.log('[AllDebrid] meta:', meta);

    showMenu(meta);
  }

  function findMountRoot(activity) {
    if (!activity.activity || typeof activity.activity.render !== 'function') return null;

    var root = activity.activity.render();
    if (!root || !root.length) return null;

    var buttons = root.find('.full-start-new__buttons');
    if (buttons.length) return buttons;

    return root;
  }

  function injectButton(activity) {
    var key = activity.card && activity.card.id != null ? String(activity.card.id) : 'full';
    if (injectedFor === key) return;

    var mount = findMountRoot(activity);
    if (!mount) return;

    if (mount.find('.button--alldebrid').length) {
      injectedFor = key;
      return;
    }

    var btn = $(
      '<div class="full-start__button selector button--alldebrid">' +
        '<div class="full-start__icon">' +
          '<svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor">' +
            '<path d="M12 2L2 7l10 5 10-5-10-5zm0 7L2 14l10 5 10-5-10-5zm0 7l-10 5 10 5 10-5-10-5z"/>' +
          '</svg>' +
        '</div>' +
        '<span>AllDebrid</span>' +
      '</div>'
    );

    btn.on('hover:enter', function () {
      onButtonClick(Lampa.Activity.active());
    });

    mount.append(btn);
    injectedFor = key;
  }

  function clearInjectedIfNotFull() {
    var activity = Lampa.Activity.active();
    if (!activity || activity.component !== 'full') {
      injectedFor = null;
    }
  }

  function poll() {
    clearInjectedIfNotFull();

    var activity = Lampa.Activity.active();

    if (activity && activity.component === 'full') {
      injectButton(activity);
    }
  }

  function init() {
    setInterval(poll, POLL_MS);
    poll();
    console.log('[AllDebrid] plugin ready');
  }

  if (window.appready) {
    init();
  } else {
    Lampa.Listener.follow('app', function (e) {
      if (e.type === 'ready') init();
    });
  }
})();
