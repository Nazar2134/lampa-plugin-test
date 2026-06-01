(function () {
  'use strict';

  /**
   * Architecture (future):
   * Movie → AllDebrid Search → Result List → Selected → Unlock → Direct URL → Lampa Player
   * Playback not implemented in this MVP.
   */

  if (window.plugin_alldebrid) return;
  window.plugin_alldebrid = true;

  var AD_BASE = 'https://api.alldebrid.com/v4';
  var AD_BASE_V41 = 'https://api.alldebrid.com/v4.1';
  var STORAGE_KEY = 'alldebrid_api_key';
  var POLL_MS = 1000;
  var buttonAdded = false;
  var pollTimer = null;

  function getApiKey() {
    return Lampa.Storage.get('alldebrid_api_key', '');
  }

  function maskApiKey(key) {
    if (!key) return '';
    if (key.length <= 8) return '********';
    return '****' + key.slice(-4);
  }

  function notifyApiKeyMissing() {
    Lampa.Noty.show('AllDebrid API key is not configured. Open Settings → AllDebrid.');
  }

  function ensureApiKeyConfigured() {
    var apiKey = getApiKey();
    if (!apiKey) {
      notifyApiKeyMissing();
      return false;
    }
    return true;
  }

  function getActiveMovie() {
    var activity = Lampa.Activity.active();
    if (!activity) return null;
    return activity.movie || activity.card || null;
  }

  function extractMovieInfo(movie) {
    if (!movie) {
      return {
        title: '',
        original_title: '',
        imdb_id: '',
        release_date: '',
        tmdb_id: ''
      };
    }

    return {
      title: movie.title || movie.name || '',
      original_title: movie.original_title || movie.original_name || '',
      imdb_id: movie.imdb_id || '',
      release_date: movie.release_date || movie.first_air_date || '',
      tmdb_id: movie.id != null ? String(movie.id) : ''
    };
  }

  function escapeHtml(str) {
    return String(str || '')
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  function buildSearchQueries(info) {
    var queries = [];
    var year = info.release_date ? String(info.release_date).slice(0, 4) : '';

    if (info.imdb_id) {
      queries.push(String(info.imdb_id).replace(/^tt/i, 'tt'));
    }

    if (info.title) {
      queries.push(info.title + (year ? ' ' + year : ''));
    }

    if (info.original_title && info.original_title !== info.title) {
      queries.push(info.original_title + (year ? ' ' + year : ''));
    }

    return queries.filter(function (q, i, arr) {
      return q && arr.indexOf(q) === i;
    });
  }

  function parseQuality(name) {
    var n = String(name || '').toLowerCase();
    var match = n.match(/\b(2160p|1080p|720p|480p|4k|8k)\b/i);
    return match ? match[1].toUpperCase() : '—';
  }

  function formatBytes(bytes) {
    var n = Number(bytes);
    if (!n || isNaN(n)) return '—';
    var units = ['B', 'KB', 'MB', 'GB', 'TB'];
    var i = 0;
    while (n >= 1024 && i < units.length - 1) {
      n /= 1024;
      i++;
    }
    return n.toFixed(i > 0 ? 1 : 0) + ' ' + units[i];
  }

  function encodeFormBody(params) {
    var parts = [];

    Object.keys(params || {}).forEach(function (key) {
      var val = params[key];
      if (val == null) return;

      if (Array.isArray(val)) {
        val.forEach(function (item) {
          parts.push(encodeURIComponent(key) + '=' + encodeURIComponent(item));
        });
      } else {
        parts.push(encodeURIComponent(key) + '=' + encodeURIComponent(val));
      }
    });

    return parts.join('&');
  }

  function buildUrlWithParams(url, params) {
    var parts = [];

    Object.keys(params || {}).forEach(function (key) {
      var val = params[key];
      if (val == null) return;

      if (Array.isArray(val)) {
        val.forEach(function (item) {
          parts.push(encodeURIComponent(key) + '=' + encodeURIComponent(item));
        });
      } else {
        parts.push(encodeURIComponent(key) + '=' + encodeURIComponent(val));
      }
    });

    if (!parts.length) return url;
    return url + (url.indexOf('?') >= 0 ? '&' : '?') + parts.join('&');
  }

  /**
   * AllDebrid API v4.1:
   * POST https://api.alldebrid.com/v4.1/magnet/status
   * Optional body: status = active | ready | expired | error
   * Auth: Authorization: Bearer <apikey> (official docs)
   */
  function adRequest(path, params, method, base) {
    var apiKey = getApiKey();

    if (!apiKey) {
      return Promise.reject(new Error('AllDebrid API key is not configured'));
    }

    method = (method || 'GET').toUpperCase();
    params = params || {};
    base = base || AD_BASE;

    return new Promise(function (resolve, reject) {
      var fullUrl = base + path;
      var requestUrl = method === 'GET' ? buildUrlWithParams(fullUrl, params) : fullUrl;

      console.log('[AllDebrid] REQUEST', method, requestUrl, params);

      var xhr = new XMLHttpRequest();
      xhr.open(method, requestUrl, true);
      xhr.timeout = 30000;
      xhr.setRequestHeader('Authorization', 'Bearer ' + apiKey);

      function finishWithResponse() {
        var status = xhr.status;
        var responseText = xhr.responseText || '';

        console.log('[AllDebrid] HTTP STATUS', status);
        console.log('[AllDebrid] RAW RESPONSE', responseText);

        var json = null;

        try {
          json = responseText ? JSON.parse(responseText) : null;
        } catch (parseErr) {
          console.error('[AllDebrid] JSON PARSE ERROR', parseErr);
          reject(
            new Error('Invalid JSON (HTTP ' + status + '): ' + responseText.slice(0, 200))
          );
          return;
        }

        console.log('[AllDebrid] RESPONSE', json);

        if (status >= 200 && status < 300 && json && json.status === 'success') {
          resolve(json.data);
          return;
        }

        if (json && json.error) {
          console.error('[AllDebrid] API ERROR', json);
          reject(
            new Error(
              (json.error.message || json.error.code || 'AllDebrid API error') +
                ' (HTTP ' +
                status +
                ')'
            )
          );
          return;
        }

        reject(new Error('HTTP ' + status + ': ' + (responseText || 'empty response')));
      }

      xhr.onload = finishWithResponse;

      xhr.onerror = function () {
        console.error('[AllDebrid] REQUEST FAILED', 'onerror', xhr.status, xhr.responseText);
        console.log('[AllDebrid] HTTP STATUS', xhr.status);
        console.log('[AllDebrid] RAW RESPONSE', xhr.responseText || '');
        reject(new Error('Network error (HTTP ' + xhr.status + ')'));
      };

      xhr.ontimeout = function () {
        console.error('[AllDebrid] REQUEST FAILED', 'timeout');
        reject(new Error('Request timeout'));
      };

      if (method === 'POST') {
        xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
        xhr.send(encodeFormBody(params));
      } else {
        xhr.send();
      }
    });
  }

  function fetchIndexerResults(query) {
    return new Promise(function (resolve, reject) {
      var network = new Lampa.Reguest();
      var url =
        'https://apibay.org/q.php?q=' +
        encodeURIComponent(query) +
        '&cat=0';

      network.silent(
        url,
        function (json) {
          if (!Array.isArray(json)) {
            resolve([]);
            return;
          }

          resolve(
            json
              .filter(function (row) {
                return row && row.id && row.id !== '0';
              })
              .map(function (row) {
                return {
                  title: row.name,
                  hash: String(row.info_hash || '').toLowerCase(),
                  size: row.size,
                  seeders: row.seeders,
                  quality: parseQuality(row.name),
                  source: 'indexer',
                  raw: row
                };
              })
          );
        },
        function (a, c) {
          reject(new Error(network.errorDecode(a, c) || 'Indexer search failed'));
        }
      );
    });
  }

  function checkInstantAvailability(hashes) {
    if (!hashes.length) return Promise.resolve([]);

    return adRequest('/magnet/instant', { 'magnets[]': hashes }, 'GET').then(function (data) {
      var magnets = (data && data.magnets) || [];
      var cached = {};

      magnets.forEach(function (m) {
        if (m && m.hash && m.instant) {
          cached[String(m.hash).toLowerCase()] = m;
        }
      });

      return cached;
    });
  }

  function fetchReadyMagnets() {
    return adRequest('/magnet/status', { status: 'ready' }, 'POST', AD_BASE_V41)
      .then(function (data) {
        return (data && data.magnets) || [];
      })
      .catch(function (err) {
        console.error('[AllDebrid] fetchReadyMagnets failed (continuing search)', err);
        return [];
      });
  }

  function matchesMovie(name, info) {
    var text = String(name || '').toLowerCase();
    if (!text) return false;

    if (info.imdb_id && text.indexOf(String(info.imdb_id).toLowerCase()) >= 0) return true;

    if (info.title && text.indexOf(String(info.title).toLowerCase()) >= 0) return true;

    if (info.original_title && text.indexOf(String(info.original_title).toLowerCase()) >= 0) {
      return true;
    }

    return false;
  }

  function searchCachedTorrents(movie) {
    var info = extractMovieInfo(movie);
    var queries = buildSearchQueries(info);
    var results = [];
    var seen = {};

    console.log('[AllDebrid] queries', queries);

    function pushResult(item) {
      var key = item.hash || item.title;
      if (!key || seen[key]) return;
      seen[key] = true;
      results.push(item);
    }

    return fetchReadyMagnets().then(function (readyList) {
        console.log('[AllDebrid] ready magnets', readyList);

        readyList.forEach(function (m) {
          if (!matchesMovie(m.filename, info)) return;

          pushResult({
            title: m.filename,
            size: formatBytes(m.size),
            seeders: m.seeders != null ? String(m.seeders) : '—',
            quality: parseQuality(m.filename),
            hash: '',
            cached: true,
            source: 'alldebrid_library',
            raw: m
          });
        });

        var chain = Promise.resolve();

        queries.forEach(function (query) {
          chain = chain
            .then(function () {
              console.log('[AllDebrid] indexer query', query);
              return fetchIndexerResults(query);
            })
            .then(function (rows) {
              console.log('[AllDebrid] indexer results', rows);

              var hashes = rows.map(function (r) {
                return r.hash;
              });

              return checkInstantAvailability(hashes).then(function (cachedMap) {
                console.log('[AllDebrid] instant cache', cachedMap);

                rows.forEach(function (row) {
                  if (!cachedMap[row.hash]) return;

                  pushResult({
                    title: row.title,
                    size: formatBytes(row.size),
                    seeders: row.seeders != null ? String(row.seeders) : '—',
                    quality: row.quality,
                    hash: row.hash,
                    cached: true,
                    source: 'instant',
                    raw: Object.assign({}, row.raw, { instant: cachedMap[row.hash] })
                  });
                });
              });
            });
        });

        return chain.then(function () {
          return results;
        });
      });
  }

  function showMovieInfoModal(info) {
    var html =
      '<div class="alldebrid-info" style="padding:1em;line-height:1.6;">' +
      '<div><b>Title</b><br>' +
      escapeHtml(info.title) +
      '</div><br>' +
      '<div><b>IMDb ID</b><br>' +
      escapeHtml(info.imdb_id || '—') +
      '</div><br>' +
      '<div><b>Release date</b><br>' +
      escapeHtml(info.release_date || '—') +
      '</div><br>' +
      '<div><b>TMDB ID</b><br>' +
      escapeHtml(info.tmdb_id || '—') +
      '</div>' +
      '</div>';

    Lampa.Modal.open({
      title: 'AllDebrid',
      html: html,
      onBack: function () {
        Lampa.Modal.close();
        Lampa.Controller.toggle('full_start');
      }
    });
  }

  function showResultsModal(info, results) {
    if (!results.length) {
      Lampa.Noty.show('No cached results found');
      showMovieInfoModal(info);
      return;
    }

    var items = results.map(function (row) {
      return {
        title: row.title,
        subtitle:
          'Size: ' +
          row.size +
          ' · Seeders: ' +
          row.seeders +
          ' · Quality: ' +
          row.quality,
        result: row,
        onSelect: function () {
          console.log('[AllDebrid] selected result:', row);
          Lampa.Noty.show('Selected (see console)');
        }
      };
    });

    items.unshift({
      title: info.title || 'Movie',
      subtitle:
        'IMDb: ' +
        (info.imdb_id || '—') +
        ' · TMDB: ' +
        (info.tmdb_id || '—') +
        ' · ' +
        (info.release_date || '—'),
      separator: true
    });

    Lampa.Select.show({
      title: 'AllDebrid — cached results',
      items: items,
      onBack: function () {
        Lampa.Controller.toggle('full_start');
      },
      onSelect: function (el) {
        if (el.onSelect) el.onSelect(el);
      }
    });
  }

  function onAllDebridClick() {
    var movie = getActiveMovie();

    if (!movie) {
      Lampa.Noty.show('No movie data');
      return;
    }

    var info = extractMovieInfo(movie);

    console.log('[AllDebrid] movie', movie);
    console.log('[AllDebrid] movie info', info);

    if (!ensureApiKeyConfigured()) {
      return;
    }

    Lampa.Loading.start(function () {
      Lampa.Loading.stop();
    });

    searchCachedTorrents(movie)
      .then(function (results) {
        Lampa.Loading.stop();
        console.log('[AllDebrid] results:', results);
        showResultsModal(info, results);
      })
      .catch(function (err) {
        Lampa.Loading.stop();

        console.group('[AllDebrid] SEARCH ERROR');
        console.error(err);
        console.error(err && err.stack);
        console.groupEnd();

        Lampa.Noty.show('AllDebrid: ' + (err.message || 'search failed'));
        showMovieInfoModal(info);
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
        '<div class="full-start__icon">' +
          '<svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor">' +
            '<path d="M12 2L2 7l10 5 10-5-10-5zm0 7L2 14l10 5 10-5-10-5zm0 7l-10 5 10 5 10-5-10-5z"/>' +
          '</svg>' +
        '</div>' +
        '<span>AllDebrid</span>' +
      '</div>'
    );

    $('.button--alldebrid').on('hover:enter click', onAllDebridClick);

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

  function updateApiKeyDisplay(body) {
    if (!body || !body.length) return;

    var key = getApiKey();
    var field = body.find('[data-name="alldebrid_api_key"] .settings-param__value');

    if (field.length) {
      field.text(key ? maskApiKey(key) : 'Not set');
    }
  }

  function addLang() {
    if (!Lampa.Lang || !Lampa.Lang.add) return;

    Lampa.Lang.add({
      alldebrid_settings_title: {
        en: 'AllDebrid',
        ru: 'AllDebrid'
      },
      alldebrid_api_key_title: {
        en: 'API Key',
        ru: 'API ключ'
      },
      alldebrid_api_key_descr: {
        en: 'Your personal AllDebrid API key (stored only on this device)',
        ru: 'Ваш личный API ключ AllDebrid (хранится только на устройстве)'
      },
      alldebrid_api_key_saved: {
        en: 'AllDebrid API key saved',
        ru: 'API ключ AllDebrid сохранён'
      },
      alldebrid_api_key_input_title: {
        en: 'AllDebrid API Key',
        ru: 'API ключ AllDebrid'
      }
    });
  }

  function translate(key, fallback) {
    return Lampa.Lang && Lampa.Lang.translate
      ? Lampa.Lang.translate(key)
      : fallback;
  }

  function saveApiKey(value, body) {
    Lampa.Storage.set('alldebrid_api_key', value || '');
    updateApiKeyDisplay(body);
    Lampa.Noty.show(translate('alldebrid_api_key_saved', 'AllDebrid API key saved'));
  }

  function openApiKeyInput(elem, body) {
    var current = getApiKey();

    if (Lampa.Input && Lampa.Input.edit) {
      Lampa.Input.edit(
        {
          elem: elem,
          name: STORAGE_KEY,
          nomic: true,
          title: translate('alldebrid_api_key_input_title', 'AllDebrid API Key'),
          value: current
        },
        function (value) {
          saveApiKey(value, body);
        }
      );
      return;
    }

    if (Lampa.Params && Lampa.Params.bind) {
      Lampa.Params.bind(elem, body);
      elem.trigger('hover:enter');
      return;
    }

    Lampa.Noty.show('Input dialog is not available in this Lampa build');
  }

  function bindApiKeyField(body) {
    var field = body.find('[data-name="alldebrid_api_key"]');
    if (!field.length) return;

    updateApiKeyDisplay(body);

    field.off('hover:enter.alldebrid').on('hover:enter.alldebrid', function () {
      openApiKeyInput($(this), body);
    });
  }

  function installSettings() {
    if (!Lampa.Params || !Lampa.Params.select) return;

    Lampa.Params.select('alldebrid_api_key', '', '');

    Lampa.Template.add(
      'settings_alldebrid',
      '<div class="settings-param selector" data-name="alldebrid_api_key">' +
        '<div class="settings-param__name">#{alldebrid_api_key_title}</div>' +
        '<div class="settings-param__value"></div>' +
        '<div class="settings-param__descr">#{alldebrid_api_key_descr}</div>' +
      '</div>'
    );

    function addSettingsMenuItem() {
      if (!Lampa.Settings || !Lampa.Settings.main) return;

      var main = Lampa.Settings.main().render();
      if (main.find('[data-component="alldebrid"]').length) return;

      var label = Lampa.Lang.translate
        ? Lampa.Lang.translate('alldebrid_settings_title')
        : 'AllDebrid';

      var item = $(
        '<div class="settings-folder selector" data-component="alldebrid">' +
          '<div class="settings-folder__icon">' +
            '<svg height="29" viewBox="0 0 24 24" fill="currentColor">' +
              '<path d="M12 2L2 7l10 5 10-5-10-5zm0 7L2 14l10 5 10-5-10-5zm0 7l-10 5 10 5 10-5-10-5z"/>' +
            '</svg>' +
          '</div>' +
          '<div class="settings-folder__name">' +
          label +
          '</div>' +
        '</div>'
      );

      var anchor = main.find('[data-component="more"]');
      if (anchor.length) anchor.after(item);
      else main.append(item);

      Lampa.Settings.main().update();
    }

    if (window.appready) addSettingsMenuItem();
    else {
      Lampa.Listener.follow('app', function (e) {
        if (e.type === 'ready') addSettingsMenuItem();
      });
    }

    Lampa.Settings.listener.follow('open', function (e) {
      if (e.name !== 'alldebrid') return;

      bindApiKeyField(e.body);
    });

    Lampa.Storage.listener.follow('change', function (e) {
      if (e.name !== STORAGE_KEY) return;

      var body = e.body || $('.settings .scroll__body').first();
      if (body && body.length) updateApiKeyDisplay(body);
    });
  }

  function init() {
    addLang();

    Lampa.Manifest.plugins = {
      type: 'other',
      version: '0.3.0',
      name: 'AllDebrid',
      description: 'AllDebrid cached torrent search'
    };

    installSettings();
    startPolling();
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
