(function () {
  'use strict';

  if (window.plugin_alldebrid) return;
  window.plugin_alldebrid = true;

  var MOCK_RESULTS = [
    {
      title: '1080p WEB-DL',
      subtitle: 'Cached · 4.2 GB · mock',
      quality: '1080p',
      size: '4.2 GB',
      cached: true
    },
    {
      title: '720p BluRay',
      subtitle: 'Cached · 2.1 GB · mock',
      quality: '720p',
      size: '2.1 GB',
      cached: true
    },
    {
      title: '2160p REMUX',
      subtitle: 'Not cached · 45 GB · mock',
      quality: '2160p',
      size: '45 GB',
      cached: false
    }
  ];

  function log() {
    var args = ['[AllDebrid]'].concat([].slice.call(arguments));
    console.log.apply(console, args);
  }

  function getCardMeta(movie) {
    if (!movie) return { title: '', original_title: '', year: '', is_tv: false };

    var isTv = Boolean(movie.name || movie.first_air_date);
    var title = movie.title || movie.name || '';
    var original = movie.original_title || movie.original_name || '';
    var date = movie.release_date || movie.first_air_date || '';
    var year = date ? String(date).slice(0, 4) : '';

    return {
      title: title,
      original_title: original,
      year: year,
      is_tv: isTv,
      tmdb_id: movie.id,
      imdb_id: movie.imdb_id || '',
      raw: movie
    };
  }

  function buildSearchQuery(meta) {
    var parts = [meta.title || meta.original_title];
    if (meta.year) parts.push(meta.year);
    return parts.filter(Boolean).join(' ');
  }

  function showResultsMenu(meta, items, onBack) {
    var menuItems = items.map(function (item) {
      return {
        title: item.title,
        subtitle: item.subtitle,
        quality: item.quality,
        onSelect: function () {
          log('Selected:', item.title, item);
          Lampa.Noty.show('AllDebrid: ' + item.title + ' (mock)');
        }
      };
    });

    menuItems.unshift({
      title: meta.title + (meta.year ? ' (' + meta.year + ')' : ''),
      subtitle: buildSearchQuery(meta),
      separator: true
    });

    Lampa.Select.show({
      title: 'AllDebrid',
      items: menuItems,
      onBack: onBack || function () {
        Lampa.Controller.toggle('full_start');
      },
      onSelect: function (el) {
        if (el.onSelect) el.onSelect(el);
      }
    });
  }

  function openAllDebrid(movie) {
    var meta = getCardMeta(movie);

    log('Metadata:', meta);
    log('Search query:', buildSearchQuery(meta));

    showResultsMenu(meta, MOCK_RESULTS);
  }

  function addButton() {
    Lampa.Listener.follow('full', function (e) {
      if (e.type !== 'complite') return;
      if (!e.data || !e.data.movie) return;

      var root = e.object.activity.render();
      var anchor = root.find('.view--torrent');
      if (!anchor.length) anchor = root.find('.full-start-new__buttons');

      if (root.find('.button--alldebrid').length) return;

      var btn = $(
        '<div class="full-start__button selector button--alldebrid">' +
          '<div class="full-start__icon">' +
            '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">' +
              '<path d="M12 2L2 7L12 12L22 7L12 2Z" stroke="currentColor" stroke-width="2" stroke-linejoin="round"/>' +
              '<path d="M2 17L12 22L22 17" stroke="currentColor" stroke-width="2" stroke-linejoin="round"/>' +
              '<path d="M2 12L12 17L22 12" stroke="currentColor" stroke-width="2" stroke-linejoin="round"/>' +
            '</svg>' +
          '</div>' +
          '<span>AllDebrid</span>' +
        '</div>'
      );

      btn.on('hover:enter', function () {
        openAllDebrid(e.data.movie);
      });

      if (anchor.length) anchor.after(btn);
      else root.find('.full-start-new__buttons').append(btn);
    });
  }

  function init() {
    Lampa.Manifest.plugins = {
      type: 'other',
      version: '0.1.0',
      name: 'AllDebrid',
      description: 'AllDebrid direct playback (prototype)'
    };

    addButton();
    log('plugin ready');
  }

  if (window.appready) {
    init();
  } else {
    Lampa.Listener.follow('app', function (e) {
      if (e.type === 'ready') init();
    });
  }
})();
