(function () {
  'use strict';

  /**
   * Movie → AllDebrid ready magnets → Match → Select → Files → Unlock → Lampa Player
   */
  var VIDEO_EXT = /\.(mkv|mp4|avi|mov)$/i;

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

  function toArray(value) {
    if (value == null) return [];
    if (Array.isArray(value)) return value;

    if (typeof value !== 'object') return [];

    if (typeof Object.values === 'function') {
      return Object.values(value);
    }

    var arr = [];
    for (var key in value) {
      if (Object.prototype.hasOwnProperty.call(value, key)) {
        arr.push(value[key]);
      }
    }
    return arr;
  }

  function debugReadyList(readyList, label) {
    label = label || 'readyList';

    console.log('[DEBUG] ' + label + ' typeof readyList', typeof readyList);
    console.log('[DEBUG] ' + label + ' Array.isArray', Array.isArray(readyList));
    console.log('[DEBUG] ' + label + ' readyList', readyList);
    console.log('[DEBUG] ' + label + ' first item', readyList && readyList[0]);

    var magnets = toArray(readyList);

    console.log('[DEBUG] ' + label + ' normalized length', magnets.length);
    console.log('[DEBUG] ' + label + ' first normalized item', magnets[0]);

    return magnets;
  }

  function extractMagnetsFromApiData(data) {
    console.log('[DEBUG] API response.data', data);

    if (!data) return [];

    if (Array.isArray(data)) return toArray(data);

    if (data.magnets != null) {
      console.log('[DEBUG] using data.magnets');
      return debugReadyList(data.magnets, 'data.magnets');
    }

    console.log('[DEBUG] using data object as magnets list');
    return debugReadyList(data, 'data');
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

  function handleAdResponseText(status, text, resolve, reject) {
    var json = null;

    try {
      json = text ? JSON.parse(text) : null;
    } catch (parseErr) {
      console.error('[AllDebrid] JSON PARSE ERROR', parseErr);
      reject(new Error('Invalid JSON (HTTP ' + status + '): ' + String(text).slice(0, 200)));
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
          (json.error.message || json.error.code || 'AllDebrid API error') + ' (HTTP ' + status + ')'
        )
      );
      return;
    }

    reject(new Error('HTTP ' + status + ': ' + (text || 'empty response')));
  }

  /**
   * AllDebrid API v4.1:
   * POST https://api.alldebrid.com/v4.1/magnet/status
   * Optional body: status = active | ready | expired | error
   * Auth: Authorization: Bearer <apikey> (required; agent param removed in v4.1+)
   */
  function adRequest(path, params, method, base) {
    var apiKey = getApiKey();

    if (!apiKey) {
      return Promise.reject(new Error('AllDebrid API key is not configured'));
    }

    method = (method || 'GET').toUpperCase();
    params = params || {};
    base = base || AD_BASE;

    var fullUrl = base + path;
    var requestUrl = method === 'GET' ? buildUrlWithParams(fullUrl, params) : fullUrl;

    console.log('[AllDebrid] REQUEST', method, requestUrl, params);
    console.log('[AllDebrid] Authorization', 'Bearer ' + maskApiKey(apiKey), '(header will be sent)');
    console.log('[AllDebrid] agent', 'not required (removed in AllDebrid API v4.1+)');
    console.log(
      '[AllDebrid] endpoint check',
      path.indexOf('/magnet/status') >= 0
        ? 'POST /v4.1/magnet/status is correct for listing magnets'
        : requestUrl
    );

    if (typeof window.fetch !== 'function') {
      return adRequestXhr(requestUrl, method, params, apiKey);
    }

    var controller = typeof AbortController !== 'undefined' ? new AbortController() : null;
    var timeoutId = setTimeout(function () {
      if (controller) {
        console.error('[AllDebrid] FETCH ERROR', 'timeout after 15 seconds');
        controller.abort();
      }
    }, 15000);

    var headers = {
      Authorization: 'Bearer ' + apiKey
    };

    var fetchOptions = {
      method: method,
      headers: headers,
      mode: 'cors'
    };

    if (controller) fetchOptions.signal = controller.signal;

    if (method === 'POST') {
      headers['Content-Type'] = 'application/x-www-form-urlencoded';
      fetchOptions.body = encodeFormBody(params);
      console.log('[AllDebrid] POST BODY', fetchOptions.body);
    }

    return window
      .fetch(requestUrl, fetchOptions)
      .then(function (response) {
        clearTimeout(timeoutId);

        console.log('[AllDebrid] HTTP STATUS', response.status);

        var headersLog = {};
        if (response.headers && response.headers.forEach) {
          response.headers.forEach(function (value, key) {
            headersLog[key] = value;
          });
        }

        console.log('[AllDebrid] RESPONSE HEADERS', headersLog);
        console.log('[AllDebrid] RESPONSE TYPE (cors/opaque)', response.type);

        if (response.type === 'opaque') {
          console.error(
            '[AllDebrid] CORS',
            'opaque response — browser blocked reading AllDebrid API body'
          );
        }

        return response.text().then(function (text) {
          return { status: response.status, text: text };
        });
      })
      .then(function (result) {
        console.log('[AllDebrid] RAW RESPONSE', result.text);

        return new Promise(function (resolveInner, rejectInner) {
          handleAdResponseText(result.status, result.text, resolveInner, rejectInner);
        });
      })
      .catch(function (err) {
        clearTimeout(timeoutId);
        console.error('[AllDebrid] FETCH ERROR', err);

        if (err && err.name === 'AbortError') {
          throw new Error('Request timeout (15s)');
        }

        var msg = String((err && err.message) || err || '').toLowerCase();

        if (msg.indexOf('failed to fetch') >= 0 || msg.indexOf('network') >= 0) {
          console.error(
            '[AllDebrid] CORS',
            'likely blocked — Lampa WebView may not allow direct https://api.alldebrid.com calls'
          );
        }

        throw err;
      });
  }

  function adRequestXhr(requestUrl, method, params, apiKey) {
    return new Promise(function (resolve, reject) {
      var xhr = new XMLHttpRequest();
      xhr.open(method, requestUrl, true);
      xhr.timeout = 15000;
      xhr.setRequestHeader('Authorization', 'Bearer ' + apiKey);

      xhr.onload = function () {
        console.log('[AllDebrid] HTTP STATUS', xhr.status);
        console.log('[AllDebrid] RAW RESPONSE', xhr.responseText || '');
        handleAdResponseText(xhr.status, xhr.responseText || '', resolve, reject);
      };

      xhr.onerror = function () {
        console.error('[AllDebrid] FETCH ERROR', 'xhr.onerror', xhr.status, xhr.responseText);
        console.log('[AllDebrid] HTTP STATUS', xhr.status);
        console.log('[AllDebrid] RAW RESPONSE', xhr.responseText || '');

        if (xhr.status === 0) {
          console.error('[AllDebrid] CORS', 'HTTP 0 — request blocked or no response (often CORS)');
        }

        reject(new Error('Network error (HTTP ' + xhr.status + ')'));
      };

      xhr.ontimeout = function () {
        console.error('[AllDebrid] FETCH ERROR', 'xhr timeout (15s)');
        reject(new Error('Request timeout (15s)'));
      };

      if (method === 'POST') {
        xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
        xhr.send(encodeFormBody(params));
      } else {
        xhr.send();
      }
    });
  }

  function fetchReadyMagnets() {
    return adRequest('/magnet/status', { status: 'ready' }, 'POST', AD_BASE_V41)
      .then(function (data) {
        return extractMagnetsFromApiData(data);
      })
      .catch(function (err) {
        console.error('[AllDebrid] fetchReadyMagnets failed (continuing search)', err);
        return [];
      });
  }

  function matchesMovie(name, info) {
    var text = String(name || '').toLowerCase();
    var filename = text;

    console.log('[AllDebrid] match check filename:', filename);

    if (!filename) {
      console.log('[AllDebrid] match fail: empty filename');
      return false;
    }

    var year = info.release_date ? String(info.release_date).slice(0, 4) : '';
    var title = info.title ? String(info.title).toLowerCase() : '';
    var original = info.original_title ? String(info.original_title).toLowerCase() : '';

    console.log('[AllDebrid] match against title:', title);
    console.log('[AllDebrid] match against original_title:', original);
    console.log('[AllDebrid] match against year:', year);

    var titleMatch = false;

    if (title && filename.indexOf(title) >= 0) {
      titleMatch = true;
      console.log('[AllDebrid] match hit: title');
    }

    if (original && filename.indexOf(original) >= 0) {
      titleMatch = true;
      console.log('[AllDebrid] match hit: original_title');
    }

    if (!titleMatch) {
      console.log('[AllDebrid] match fail: no title match');
      return false;
    }

    if (year && filename.indexOf(year) < 0) {
      console.log('[AllDebrid] match fail: year not in filename');
      return false;
    }

    console.log('[AllDebrid] match success');
    return true;
  }

  function filterMagnetsForMovie(magnets, info) {
    var list = toArray(magnets);

    console.log('[AllDebrid] filter step 1: input count', list.length);

    if (!Array.isArray(list)) {
      console.warn('[AllDebrid] filter abort: magnets is not an array after toArray');
      return [];
    }

    var matched = [];

    for (var i = 0; i < list.length; i++) {
      var m = list[i];
      var label = m && (m.filename || m.name) ? m.filename || m.name : '(no name)';

      console.log('[AllDebrid] filter step 2: index', i, 'name', label);

      if (matchesMovie(label, info)) {
        console.log('[AllDebrid] filter step 3: KEEP', label);
        matched.push(m);
      } else {
        console.log('[AllDebrid] filter step 3: SKIP', label);
      }
    }

    console.log('[AllDebrid] filter step 4: matched count', matched.length);

    return matched;
  }

  function flattenMagnetFiles(nodes, pathPrefix) {
    var out = [];
    var prefix = pathPrefix || '';
    var list = toArray(nodes);

    if (!Array.isArray(list)) return out;

    list.forEach(function (node) {
      if (!node) return;

      if (node.e) {
        var children = toArray(node.e);

        if (children.length) {
          var folder = node.n ? prefix + node.n + '/' : prefix;
          out = out.concat(flattenMagnetFiles(children, folder));
        }
      } else if (node.l) {
        out.push({
          name: prefix + (node.n || 'unknown'),
          size: node.s,
          link: node.l
        });
      }
    });

    return out;
  }

  function filterVideoFiles(files) {
    var list = toArray(files);

    if (!Array.isArray(list)) return [];

    return list.filter(function (f) {
      return VIDEO_EXT.test(f.name);
    });
  }

  function fetchMagnetFiles(magnetId) {
    return adRequest('/magnet/files', { 'id[]': [String(magnetId)] }, 'POST').then(function (data) {
      var magnets = extractMagnetsFromApiData(data);
      return magnets[0] || { id: magnetId, files: [] };
    });
  }

  function unlockLink(link) {
    return adRequest('/link/unlock', { link: link }, 'POST');
  }

  function openLampaPlayer(url, movie, info, fileName) {
    console.log('[AllDebrid] stream url', url);
    console.log('[AllDebrid] opening player');

    if (!url) {
      Lampa.Noty.show('Unable to create stream');
      return;
    }

    try {
      if (!Lampa.Player || !Lampa.Player.play) {
        throw new Error('Lampa.Player.play not available');
      }

      Lampa.Player.play({
        url: url,
        title: info.title || fileName || 'AllDebrid',
        card: movie
      });
    } catch (err) {
      console.error('[AllDebrid] player error', err);
      Lampa.Noty.show('Playback failed');
    }
  }

  function playVideoFile(file, movie, info) {
    console.log('[AllDebrid] selected file', file);

    return unlockLink(file.link)
      .then(function (response) {
        console.log('[AllDebrid] unlock response', response);
        Lampa.Loading.stop();
        openLampaPlayer(response && response.link, movie, info, file.name);
      })
      .catch(function (err) {
        Lampa.Loading.stop();
        console.error('[AllDebrid] unlock failed', err);
        Lampa.Noty.show('Unable to create stream');
      });
  }

  function showVideoFilePicker(videoFiles, movie, info) {
    var items = videoFiles.map(function (file) {
      return {
        title: file.name,
        subtitle: formatBytes(file.size),
        file: file,
        onSelect: function () {
          Lampa.Loading.start(function () {
            Lampa.Loading.stop();
          });
          playVideoFile(file, movie, info);
        }
      };
    });

    Lampa.Select.show({
      title: 'Select file',
      items: items,
      onBack: function () {
        Lampa.Controller.toggle('full_start');
      },
      onSelect: function (el) {
        if (el.onSelect) el.onSelect(el);
      }
    });
  }

  function handleResultSelect(row, movie, info) {
    var magnet = row.raw;

    console.log('[AllDebrid] selected magnet', magnet);

    if (!magnet || magnet.id == null) {
      Lampa.Noty.show('Invalid magnet');
      return;
    }

    Lampa.Loading.start(function () {
      Lampa.Loading.stop();
    });

    fetchMagnetFiles(magnet.id)
      .then(function (details) {
        console.log('[AllDebrid] magnet details', details);

        var allFiles = flattenMagnetFiles(details.files || []);
        var videoFiles = filterVideoFiles(allFiles);

        console.log('[AllDebrid] video files', videoFiles);

        if (!videoFiles.length) {
          Lampa.Loading.stop();
          Lampa.Noty.show('No playable files');
          return;
        }

        if (videoFiles.length === 1) {
          return playVideoFile(videoFiles[0], movie, info);
        }

        Lampa.Loading.stop();
        showVideoFilePicker(videoFiles, movie, info);
      })
      .catch(function (err) {
        Lampa.Loading.stop();
        console.error('[AllDebrid] magnet files failed', err);
        Lampa.Noty.show('Unable to create stream');
      });
  }

  function searchCachedTorrents(movie) {
    var info = extractMovieInfo(movie);
    var results = [];
    var seen = {};

    console.log('[AllDebrid] search for', info);

    function pushResult(item) {
      var key = String(item.magnetId || item.title);
      if (!key || seen[key]) return;
      seen[key] = true;
      results.push(item);
    }

    return fetchReadyMagnets().then(function (readyList) {
      console.log('[AllDebrid] ready magnets', readyList);

      var magnets = debugReadyList(readyList, 'fetchReadyMagnets');

      if (!Array.isArray(magnets)) {
        console.warn('[AllDebrid] magnets is not an array, forcing toArray');
        magnets = toArray(magnets);
      }

      var matched = filterMagnetsForMovie(magnets, info);

      for (var i = 0; i < matched.length; i++) {
        var m = matched[i];

        pushResult({
          title: m.filename || m.name || 'Unknown',
          size: formatBytes(m.size),
          seeders: m.seeders != null ? String(m.seeders) : '—',
          quality: parseQuality(m.filename || m.name),
          magnetId: m.id,
          cached: true,
          source: 'alldebrid_library',
          raw: m
        });
      }

      console.log('[AllDebrid] results array length', results.length);

      return results;
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

  function showResultsModal(info, results, movie) {
    console.log('[DEBUG] showResultsModal typeof results', typeof results);
    console.log('[DEBUG] showResultsModal Array.isArray', Array.isArray(results));

    var resultsList = toArray(results);

    console.log('[DEBUG] showResultsModal resultsList length', resultsList.length);

    if (!Array.isArray(resultsList) || !resultsList.length) {
      Lampa.Noty.show('No cached results found');
      showMovieInfoModal(info);
      return;
    }

    var items = resultsList.map(function (row) {
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
          console.log('[AllDebrid] selected result (playback disabled):', row);
          Lampa.Noty.show('Selected: ' + row.title);
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

    var selectItems = Array.isArray(items) ? items : toArray(items);

    console.log('[DEBUG] Select items Array.isArray', Array.isArray(selectItems));
    console.log('[DEBUG] Select items length', selectItems.length);

    Lampa.Select.show({
      title: 'AllDebrid — cached results',
      items: selectItems,
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

        var resultsList = toArray(results);

        console.log('[AllDebrid] results:', resultsList);
        showResultsModal(info, resultsList, movie);
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
      version: '1.0.0',
      name: 'AllDebrid',
      description: 'AllDebrid cached playback'
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
