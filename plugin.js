(function () {
  'use strict';

  /**
   * MVP: AllDebrid ready magnets → title match → select → files → unlock → player
   */
  var VIDEO_EXT = /\.(mkv|mp4|avi|mov)$/i;
  var INSTANT_BATCH = 50;
  var MAX_CANDIDATES = 150;
  var MAX_SEARCH_QUERIES = 3;
  var APIBAY_URL = 'https://apibay.org/q.php';

  if (window.plugin_alldebrid) return;
  window.plugin_alldebrid = true;

  var AD_BASE = 'https://api.alldebrid.com/v4';
  var AD_BASE_V41 = 'https://api.alldebrid.com/v4.1';
  var STORAGE_KEY = 'alldebrid_api_key';
  var POLL_MS = 1000;
  var pollTimer = null;
  var lastMovieId = null;
  var lastMountKey = null;
  var buttonSyncTimers = [];
  var activeSearchId = 0;

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

  /**
   * Movie/card data sources in this plugin:
   * - getActiveMovie()           → Lampa.Activity.active().movie || .card
   * - snapshotMovie()            → frozen copy at AllDebrid click (used for search)
   * - buildSearchQueries()       → reads activity.search / search_one / search_two
   * - getMovieForButton()        → full event data.movie || props.get('movie') || getActiveMovie()
   * - syncAllDebridButton()      → getMovieForButton(fullEvent) || getActiveMovie()
   * - onAllDebridClick()         → getActiveMovie() → snapshotMovie() → searchCachedTorrents(movie)
   * - searchCachedTorrents(movie) → passes snapshot to public + account paths
   * - searchAccountLibraryFallback → compares snapshot vs getActiveMovie() after async fetch
   * Not used: currentMovie, cachedMovie, selectedMovie (no such vars in plugin)
   * - lastMovieId                → button UI only, NOT search matching
   */
  function logMovieDataSources(label, searchMovie) {
    var activity = Lampa.Activity.active();
    var liveMovie = null;
    var liveCard = null;

    if (activity) {
      liveMovie = activity.movie || null;
      liveCard = activity.card || null;
    }

    var liveFromGetter = getActiveMovie();

    console.group('[MOVIE DATA] ' + (label || 'unknown'));
    console.log('[ACTIVE ACTIVITY]', activity);
    console.log('[ACTIVE MOVIE]', liveMovie);
    console.log('[ACTIVE CARD]', liveCard);
    console.log('[SEARCH MOVIE]', searchMovie);
    console.log('[LIVE getActiveMovie()]', liveFromGetter);

    if (searchMovie && liveFromGetter) {
      var sameRef = searchMovie === liveFromGetter;
      var sameId =
        searchMovie.id != null &&
        liveFromGetter.id != null &&
        String(searchMovie.id) === String(liveFromGetter.id);

      console.log('[COMPARE] same object reference:', sameRef);
      console.log('[COMPARE] same id:', sameId);
      console.log('[COMPARE] search title:', searchMovie.title || searchMovie.name);
      console.log('[COMPARE] live title:', liveFromGetter.title || liveFromGetter.name);
    }

    if (searchMovie && liveMovie && searchMovie !== liveMovie) {
      console.log('[COMPARE] search vs activity.movie id', searchMovie.id, liveMovie.id);
    }

    if (searchMovie && liveCard && searchMovie !== liveCard) {
      console.log('[COMPARE] search vs activity.card id', searchMovie.id, liveCard.id);
    }

    console.log('[BUTTON STATE] lastMovieId (UI only):', lastMovieId);
    console.groupEnd();
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

  /** Immutable copy — Lampa mutates activity.card during async API calls. */
  function snapshotMovie(movie) {
    if (!movie) return null;

    return {
      id: movie.id != null ? movie.id : null,
      title: String(movie.title || movie.name || ''),
      name: String(movie.name || movie.title || ''),
      original_title: String(movie.original_title || movie.original_name || ''),
      original_name: String(movie.original_name || movie.original_title || ''),
      release_date: movie.release_date || movie.first_air_date || '',
      first_air_date: movie.first_air_date || movie.release_date || '',
      imdb_id: movie.imdb_id || '',
      tmdb_id: movie.id != null ? String(movie.id) : ''
    };
  }

  function isSearchStale(searchId) {
    return searchId != null && searchId !== activeSearchId;
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

  function logReadyListItems(readyList, label) {
    var list = toArray(readyList);

    console.log('[PIPELINE]', label || 'readyList', '— logReadyListItems');
    console.log('[DEBUG] readyList length', list.length);

    list.forEach(function (item, index) {
      console.log('[READY]', index, item && (item.filename || item.name || item.title || ''));
    });

    return list;
  }

  function debugReadyList(readyList, label) {
    label = label || 'readyList';

    console.log('[DEBUG] ' + label + ' typeof readyList', typeof readyList);
    console.log('[DEBUG] ' + label + ' Array.isArray', Array.isArray(readyList));
    console.log('[DEBUG] ' + label + ' readyList', readyList);
    console.log('[DEBUG] ' + label + ' first item', readyList && readyList[0]);

    var magnets = logReadyListItems(readyList, label);

    console.log('[DEBUG] ' + label + ' normalized length', magnets.length);
    console.log('[DEBUG] ' + label + ' first normalized item', magnets[0]);

    return magnets;
  }

  function logCandidateList(candidates, label) {
    var list = toArray(candidates);
    var isPreFilter =
      label &&
      (label.indexOf('before') !== -1 ||
        label.indexOf('merged') !== -1 ||
        label.indexOf('ready magnets') !== -1);
    var tag = isPreFilter ? '[PRE-FILTER]' : '[CANDIDATE]';

    console.log('[PIPELINE]', label || 'candidates', '— listing', list.length, 'items');

    list.forEach(function (item, index) {
      var name =
        (item &&
          (item.filename ||
            item.name ||
            item.title ||
            item.Title ||
            '')) ||
        '';
      console.log(tag, index, name);
    });

    return list;
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
    console.log('[PIPELINE] fetchReadyMagnets — request POST /magnet/status status=ready');

    return adRequest('/magnet/status', { status: 'ready' }, 'POST', AD_BASE_V41)
      .then(function (data) {
        var readyList = extractMagnetsFromApiData(data);
        console.log('[PIPELINE] fetchReadyMagnets — response normalized count', readyList.length);
        return readyList;
      })
      .catch(function (err) {
        console.error('[AllDebrid] fetchReadyMagnets failed (continuing search)', err);
        return [];
      });
  }

  function buildSearchQueries(movie) {
    logMovieDataSources('buildSearchQueries', movie);

    var info = extractMovieInfo(movie);
    var year = info.release_date ? String(info.release_date).slice(0, 4) : '';
    var queries = [];
    var seen = {};

    function add(q) {
      q = String(q || '')
        .replace(/\s+/g, ' ')
        .trim();
      if (!q || seen[q]) return;
      seen[q] = true;
      queries.push(q);
    }

    if (info.title) {
      if (year) add(info.title + ' ' + year);
      add(info.title);
    }

    if (info.original_title && info.original_title !== info.title) {
      if (year) add(info.original_title + ' ' + year);
      add(info.original_title);
    }

    var activity = Lampa.Activity.active();
    if (activity) {
      add(activity.search);
      add(activity.search_one);
      add(activity.search_two);
    }

    console.log('[AllDebrid] search queries', queries);
    return queries;
  }

  function extractInfoHash(magnetOrHash) {
    var value = String(magnetOrHash || '').trim();
    if (!value) return '';

    if (/^[a-f0-9]{40}$/i.test(value)) return value.toLowerCase();

    var match = value.match(/btih:([a-f0-9]{40})/i);
    return match ? match[1].toLowerCase() : '';
  }

  function requestJson(url, timeoutMs) {
    timeoutMs = timeoutMs || 15000;

    return new Promise(function (resolve, reject) {
      if (typeof window.fetch === 'function') {
        var controller = typeof AbortController !== 'undefined' ? new AbortController() : null;
        var timeoutId = setTimeout(function () {
          if (controller) controller.abort();
        }, timeoutMs);

        var opts = { method: 'GET', mode: 'cors' };
        if (controller) opts.signal = controller.signal;

        window
          .fetch(url, opts)
          .then(function (response) {
            clearTimeout(timeoutId);
            return response.text();
          })
          .then(function (text) {
            try {
              resolve(text ? JSON.parse(text) : null);
            } catch (err) {
              reject(err);
            }
          })
          .catch(function (err) {
            clearTimeout(timeoutId);
            reject(err);
          });
        return;
      }

      var xhr = new XMLHttpRequest();
      xhr.open('GET', url, true);
      xhr.timeout = timeoutMs;

      xhr.onload = function () {
        try {
          resolve(xhr.responseText ? JSON.parse(xhr.responseText) : null);
        } catch (err) {
          reject(err);
        }
      };

      xhr.onerror = function () {
        reject(new Error('Network error'));
      };

      xhr.ontimeout = function () {
        reject(new Error('Request timeout'));
      };

      xhr.send();
    });
  }

  function ensureMovieForParser(movie) {
    if (!movie.genres) movie.genres = [];
    return movie;
  }

  function searchViaParser(movie, query) {
    return new Promise(function (resolve) {
      if (!Lampa.Parser || typeof Lampa.Parser.get !== 'function') {
        logMovieDataSources('searchViaParser — skipped (no Parser), movie arg', movie);
        console.log('[AllDebrid] Lampa.Parser not available');
        resolve([]);
        return;
      }

      if (!query) {
        resolve([]);
        return;
      }

      var params = {
        search: query,
        movie: ensureMovieForParser(movie),
        other: false,
        global: false
      };

      console.log('[AllDebrid] Parser.get', query);

      Lampa.Parser.get(
        params,
        function (data) {
          var results = data && data.Results ? data.Results : [];
          console.log('[PIPELINE] searchViaParser — query:', query, 'raw results:', results.length);
          resolve(results);
        },
        function (err) {
          console.warn('[AllDebrid] Parser search failed', err);
          resolve([]);
        }
      );
    });
  }

  function searchViaApibay(query) {
    if (!query) return Promise.resolve([]);

    var url = APIBAY_URL + '?q=' + encodeURIComponent(query) + '&cat=0';

    console.log('[AllDebrid] apibay search', query);

    return requestJson(url)
      .then(function (data) {
        if (!Array.isArray(data) || !data.length) return [];
        if (data[0] && data[0].id === '0' && data[0].name === 'No results returned') return [];

        var mapped = data
          .map(function (item) {
            var hash = extractInfoHash(item.info_hash);
            if (!hash) return null;

            return {
              Title: item.name,
              filename: item.name,
              MagnetUri: 'magnet:?xt=urn:btih:' + hash,
              hash: hash,
              Size: parseInt(item.size, 10) || 0,
              Seeders: parseInt(item.seeders, 10) || 0,
              source: 'apibay'
            };
          })
          .filter(Boolean);

        console.log('[PIPELINE] searchViaApibay — query:', query, 'raw results:', mapped.length);
        return mapped;
      })
      .catch(function (err) {
        console.warn('[AllDebrid] apibay search failed', err);
        return [];
      });
  }

  function mergeTorrentCandidates(lists) {
    var merged = [];
    var hashSeen = {};
    var totalInput = 0;

    console.log('[PIPELINE] mergeTorrentCandidates — input list count:', lists.length);

    lists.forEach(function (list, listIndex) {
      var arr = toArray(list);
      totalInput += arr.length;
      console.log('[PIPELINE] mergeTorrentCandidates — source[' + listIndex + '] length:', arr.length);
    });

    console.log('[DEBUG] candidate source length (merge input total)', totalInput);

    lists.forEach(function (list) {
      toArray(list).forEach(function (item) {
        if (!item) return;

        var hash = item.hash || extractInfoHash(item.MagnetUri || item.magnet);
        if (!hash || hashSeen[hash]) return;

        hashSeen[hash] = true;
        item.hash = hash;
        merged.push(item);
      });
    });

    console.log('[PIPELINE] mergeTorrentCandidates — after dedupe:', merged.length);

    merged.sort(function (a, b) {
      return (b.Seeders || b.seeders || 0) - (a.Seeders || a.seeders || 0);
    });

    var sliced = merged.slice(0, MAX_CANDIDATES);
    console.log('[PIPELINE] mergeTorrentCandidates — after MAX_CANDIDATES(' + MAX_CANDIDATES + '):', sliced.length);
    console.log('[DEBUG] candidates length (post-merge)', sliced.length);

    return sliced;
  }

  function searchTorrentCandidates(movie) {
    logMovieDataSources('searchTorrentCandidates — start', movie);

    var queries = buildSearchQueries(movie).slice(0, MAX_SEARCH_QUERIES);
    var tasks = [];

    console.log('[PIPELINE] searchTorrentCandidates — start (public indexers, NOT account readyList)');
    console.log('[PIPELINE] searchTorrentCandidates — queries:', queries);

    queries.forEach(function (query) {
      tasks.push(searchViaParser(movie, query));
      tasks.push(searchViaApibay(query));
    });

    if (!tasks.length) return Promise.resolve([]);

    return Promise.all(tasks).then(function (all) {
      console.log('[PIPELINE] searchTorrentCandidates — Promise.all returned', all.length, 'sources');
      var candidates = mergeTorrentCandidates(all);
      console.log('[CANDIDATES VARIABLE]', candidates);
      console.log('[CANDIDATES LENGTH]', candidates.length);
      logCandidateList(candidates, 'public search merged');
      return candidates;
    });
  }

  function parseInstantMap(data, batchHashes) {
    var map = {};
    var magnets = data && data.magnets != null ? data.magnets : data;

    if (Array.isArray(magnets)) {
      magnets.forEach(function (entry, index) {
        if (!entry) return;

        var hash = extractInfoHash(entry.hash || entry.magnet || batchHashes[index]);
        if (!hash) return;

        map[hash] = entry.instant === true || entry.ready === true;
      });
      return map;
    }

    if (magnets && typeof magnets === 'object') {
      Object.keys(magnets).forEach(function (key) {
        var entry = magnets[key];
        var hash = extractInfoHash(
          (entry && (entry.hash || entry.magnet)) || key
        );
        if (!hash) return;

        if (typeof entry === 'boolean') {
          map[hash] = entry;
        } else if (entry && typeof entry === 'object') {
          map[hash] = entry.instant === true || entry.ready === true;
        }
      });
    }

    return map;
  }

  function checkInstantAvailability(hashes) {
    var unique = [];
    var seen = {};

    toArray(hashes).forEach(function (hash) {
      hash = extractInfoHash(hash);
      if (hash && !seen[hash]) {
        seen[hash] = true;
        unique.push(hash);
      }
    });

    if (!unique.length) return Promise.resolve({});

    console.log('[AllDebrid] instant check hashes', unique.length);

    var batches = [];
    for (var i = 0; i < unique.length; i += INSTANT_BATCH) {
      batches.push(unique.slice(i, i + INSTANT_BATCH));
    }

    return batches.reduce(function (chain, batch) {
      return chain.then(function (combined) {
        return adRequest('/magnet/instant', { 'magnets[]': batch }, 'GET').then(
          function (data) {
            var map = parseInstantMap(data, batch);
            Object.keys(map).forEach(function (hash) {
              combined[hash] = map[hash];
            });
            console.log('[AllDebrid] instant batch cached', Object.keys(map).filter(function (h) {
              return map[h];
            }).length);
            return combined;
          }
        );
      });
    }, Promise.resolve({}));
  }

  function uploadMagnet(magnetOrHash) {
    var magnet = String(magnetOrHash || '').trim();
    if (/^[a-f0-9]{40}$/i.test(magnet)) {
      magnet = 'magnet:?xt=urn:btih:' + magnet;
    }

    return adRequest('/magnet/upload', { 'magnets[]': [magnet] }, 'POST').then(function (data) {
      var magnets = data && data.magnets != null ? data.magnets : data;
      var list = toArray(magnets);
      return list[0] || null;
    });
  }

  function resolveMagnetId(row) {
    if (row.magnetId != null) return Promise.resolve(row.magnetId);

    var raw = row.raw;
    if (raw && raw.id != null) return Promise.resolve(raw.id);

    var magnetUri =
      row.magnetUri ||
      (raw && (raw.MagnetUri || raw.magnet)) ||
      (row.hash ? 'magnet:?xt=urn:btih:' + row.hash : '');

    if (!magnetUri && row.hash) {
      magnetUri = 'magnet:?xt=urn:btih:' + row.hash;
    }

    if (!magnetUri) {
      return Promise.reject(new Error('No magnet link'));
    }

    console.log('[AllDebrid] uploading magnet', magnetUri);

    return uploadMagnet(magnetUri).then(function (uploaded) {
      if (!uploaded || uploaded.id == null) {
        throw new Error('Magnet upload failed');
      }
      return uploaded.id;
    });
  }

  function matchesTorrentCandidate(candidate, movie) {
    var label =
      candidate.Title ||
      candidate.filename ||
      candidate.name ||
      candidate.title ||
      '';

    return scoreMovieAgainstFilename(movie, label) >= MIN_MATCH_SCORE;
  }

  function buildResultRow(candidate, source) {
    var title =
      candidate.Title ||
      candidate.filename ||
      candidate.name ||
      'Unknown';

    return {
      title: title,
      size: formatBytes(candidate.Size || candidate.size),
      seeders:
        candidate.Seeders != null
          ? String(candidate.Seeders)
          : candidate.seeders != null
            ? String(candidate.seeders)
            : '—',
      quality: parseQuality(title),
      magnetId: candidate.id != null ? candidate.id : null,
      hash: candidate.hash,
      magnetUri: candidate.MagnetUri || candidate.magnet,
      cached: true,
      source: source || candidate.source || 'search',
      raw: candidate
    };
  }

  function searchPublicCachedTorrents(movie, searchId) {
    logMovieDataSources('searchPublicCachedTorrents — start', movie);
    console.log('[PIPELINE] searchPublicCachedTorrents — start (path: Parser + apibay → title filter → instant)');

    return searchTorrentCandidates(movie)
      .then(function (candidates) {
        console.log('[CANDIDATES VARIABLE]', candidates);
        console.log('[CANDIDATES LENGTH]', candidates.length);

        if (isSearchStale(searchId)) {
          console.log('[PIPELINE] searchPublicCachedTorrents — stale search, abort');
          return [];
        }

        console.log('[DEBUG] candidate source length (before title filter)', candidates.length);
        logCandidateList(candidates, 'before title filter');

        var matched = [];

        candidates.forEach(function (candidate) {
          var label =
            candidate.Title ||
            candidate.filename ||
            candidate.name ||
            candidate.title ||
            '';
          var score = scoreMovieAgainstFilename(movie, label);

          logPerCandidateMatch(movie, label, score);
          logMatchScore(movie, label, score);

          if (score >= MIN_MATCH_SCORE) {
            candidate._matchScore = score;
            matched.push(candidate);
          }
        });

        matched.sort(function (a, b) {
          return (b._matchScore || 0) - (a._matchScore || 0);
        });

        console.log('[DEBUG] candidates length (after title filter)', matched.length);
        logCandidateList(matched, 'after title filter');
        console.log('[PIPELINE] searchPublicCachedTorrents — title-matched:', matched.length);

        if (!matched.length) {
          console.log('[PIPELINE] searchPublicCachedTorrents — no title matches, returning []');
          return [];
        }

        var hashes = matched.map(function (candidate) {
          return candidate.hash;
        });

        console.log('[PIPELINE] searchPublicCachedTorrents — instant check for', hashes.length, 'hashes');

        return checkInstantAvailability(hashes).then(function (instantMap) {
          var instantHits = Object.keys(instantMap).filter(function (h) {
            return instantMap[h];
          }).length;
          console.log('[PIPELINE] searchPublicCachedTorrents — instant hits:', instantHits, '/', hashes.length);

          var results = [];
          var seen = {};

          matched.forEach(function (candidate) {
            if (!instantMap[candidate.hash]) return;

            var row = buildResultRow(candidate, 'instant');
            row.matchScore = candidate._matchScore;
            var key = row.hash || row.title;
            if (!key || seen[key]) return;

            seen[key] = true;
            results.push(row);
          });

          results.sort(function (a, b) {
            var scoreDiff = (b.matchScore || 0) - (a.matchScore || 0);
            if (scoreDiff) return scoreDiff;
            return parseInt(b.seeders, 10) - parseInt(a.seeders, 10);
          });

          console.log('[DEBUG] candidates length (after instant filter)', results.length);
          console.log('[PIPELINE] searchPublicCachedTorrents — final public results:', results.length);
          return results;
        });
      })
      .catch(function (err) {
        console.error('[AllDebrid] public search failed', err);
        return [];
      });
  }

  function searchAccountLibraryFallback(movie, searchId) {
    logMovieDataSources('searchAccountLibraryFallback — start', movie);
    console.log('[PIPELINE] searchAccountLibraryFallback — MVP (readyList → title filter → results)');
    console.log('[STEP] snapshot movie id', movie && movie.id, 'title', movie && movie.title);

    return fetchReadyMagnets().then(function (readyList) {
      if (isSearchStale(searchId)) {
        console.log('[STEP] searchAccountLibraryFallback — stale search after fetchReadyMagnets, abort');
        return [];
      }

      console.log('[STEP] fetchReadyMagnets output count', toArray(readyList).length);
      console.log('[DEBUG] readyList length', toArray(readyList).length);

      logMovieDataSources('searchAccountLibraryFallback — after fetchReadyMagnets', movie);

      var liveMovie = getActiveMovie();
      if (liveMovie && movie && String(liveMovie.id) !== String(movie.id)) {
        console.warn('[STEP] activity.card changed during fetch — was matching wrong movie', {
          snapshotId: movie.id,
          snapshotTitle: movie.title,
          liveId: liveMovie.id,
          liveTitle: liveMovie.title || liveMovie.name
        });
      }

      var magnets = debugReadyList(readyList, 'account fallback');

      if (!Array.isArray(magnets)) {
        magnets = toArray(magnets);
      }

      console.log('[STEP] normalized magnets input count', magnets.length);
      console.log('[DEBUG] candidate source length (account, before title filter)', magnets.length);
      logCandidateList(magnets, 'account ready magnets before filter');

      var matched = filterMagnetsForMovie(magnets, movie);

      console.log('[STEP] filterMagnetsForMovie output count', matched.length);
      console.log('[DEBUG] candidates length (account, after title filter)', matched.length);
      logCandidateList(matched, 'account after title filter');

      var results = [];
      var seen = {};

      console.log('[STEP] buildResultRow input count', matched.length);

      for (var i = 0; i < matched.length; i++) {
        var m = matched[i];
        var row = buildResultRow(m, 'alldebrid_library');
        row.magnetId = m.id;

        var key = String(row.magnetId || row.hash || row.title);
        if (!key || seen[key]) continue;

        seen[key] = true;
        results.push(row);
      }

      console.log('[STEP] searchAccountLibraryFallback final output count', results.length);
      console.log('[AllDebrid] account fallback results', results.length);
      return results;
    });
  }

  var MIN_MATCH_SCORE = 0.45;
  var MATCH_STOP_WORDS = {
    the: 1,
    a: 1,
    an: 1,
    and: 1,
    or: 1,
    but: 1,
    in: 1,
    on: 1,
    at: 1,
    to: 1,
    for: 1,
    of: 1,
    with: 1,
    from: 1,
    by: 1,
    is: 1,
    are: 1,
    as: 1,
    it: 1,
    its: 1,
    vs: 1,
    la: 1,
    le: 1,
    les: 1,
    el: 1,
    los: 1,
    las: 1,
    der: 1,
    die: 1,
    das: 1
  };

  function normalizeTitle(str) {
    return String(str || '')
      .toLowerCase()
      .replace(/[''`]/g, '')
      .replace(/\./g, ' ')
      .replace(/(\w)\s+s\b/g, '$1s')
      .replace(/[^a-z0-9\s\u0400-\u04ff]/gi, ' ')
      .replace(/\s+/g, ' ')
      .trim();
  }

  function collapseSpaces(str) {
    return String(str || '').replace(/\s+/g, '');
  }

  function containsWord(haystack, word) {
    if (!word || !haystack) return false;
    return (' ' + haystack + ' ').indexOf(' ' + word + ' ') >= 0;
  }

  function stemToken(word) {
    if (!word || word.length < 4) return word;
    if (word.length > 4 && word.charAt(word.length - 1) === 's') {
      return word.slice(0, -1);
    }
    return word;
  }

  function wordMatchesInFilename(normFilename, word) {
    if (!word || !normFilename) return false;

    if (containsWord(normFilename, word)) return true;

    var stem = stemToken(word);
    var tokens = normFilename.split(' ');

    for (var i = 0; i < tokens.length; i++) {
      var token = tokens[i];
      if (!token) continue;
      if (token === word || token === stem) return true;
      if (stem.length >= 4 && stemToken(token) === stem) return true;
    }

    var compact = collapseSpaces(normFilename);
    if (word.length >= 4 && compact.indexOf(word) >= 0) return true;
    if (stem.length >= 4 && compact.indexOf(stem) >= 0) return true;

    return false;
  }

  function compactContainsWordSequence(compactFilename, words) {
    if (!compactFilename || !words || !words.length) return false;

    var pos = 0;

    for (var i = 0; i < words.length; i++) {
      var stem = stemToken(words[i]);
      if (!stem || stem.length < 3) continue;

      var idx = compactFilename.indexOf(stem, pos);
      if (idx < 0) return false;
      pos = idx + stem.length;
    }

    return true;
  }

  function isMatchDiagnosticFilename(str) {
    return /widows?|mummy|undertone|dead\s*body|dhurandhar/i.test(String(str || ''));
  }

  function isMatchDiagnosticTitle(str) {
    return isMatchDiagnosticFilename(str);
  }

  function logMatchDiagnostic(tag, payload) {
    console.log('[MATCH DIAG] ' + tag, payload);
  }

  function logPerCandidateMatch(movie, filename, score) {
    var movieTitle = movie.title || movie.name || '';
    var passes = score >= MIN_MATCH_SCORE;
    var diag =
      isMatchDiagnosticFilename(filename) ||
      isMatchDiagnosticTitle(movieTitle) ||
      isMatchDiagnosticTitle(movie.original_title || movie.original_name);

    if (!diag && passes) return;

    var payload = {
      originalFilename: filename,
      normalizedFilename: normalizeTitle(filename),
      movieTitle: movieTitle,
      normalizedMovieTitle: normalizeTitle(movieTitle),
      titleVariants: movieTitleVariants(movie),
      score: score,
      threshold: MIN_MATCH_SCORE,
      matched: passes
    };

    console.log('[CANDIDATE MATCH]', payload);
  }

  function getSignificantWords(normTitle) {
    if (!normTitle) return [];

    return normTitle.split(' ').filter(function (word) {
      if (!word || word.length < 3) return false;
      if (MATCH_STOP_WORDS[word]) return false;
      if (/^\d{4}$/.test(word)) return false;
      return true;
    });
  }

  function getCompactTitle(normTitle) {
    return getSignificantWords(normTitle).join('');
  }

  function collectTitleVariants(raw) {
    var variants = [];
    var seen = {};

    function add(part) {
      var norm = normalizeTitle(part);
      if (!norm || norm.length < 3 || seen[norm]) return;
      if (/^\d{4}$/.test(norm)) return;
      seen[norm] = true;
      variants.push(norm);
    }

    if (!raw) return variants;

    add(raw);

    String(raw)
      .split(/[:;|]/)
      .forEach(function (segment) {
        add(segment);
      });

    return variants;
  }

  function movieTitleVariants(movie) {
    var variants = [];
    var seen = {};

    function merge(list) {
      list.forEach(function (v) {
        if (!seen[v]) {
          seen[v] = true;
          variants.push(v);
        }
      });
    }

    merge(collectTitleVariants(movie.title || movie.name));
    merge(collectTitleVariants(movie.original_title || movie.original_name));

    return variants;
  }

  function scoreTitleAgainstFilename(normTitle, normFilename, originalFilename) {
    if (!normTitle || !normFilename) return 0;

    var score = 0;
    var diag =
      originalFilename &&
      (isMatchDiagnosticFilename(originalFilename) || isMatchDiagnosticFilename(normFilename));

    if (normFilename.indexOf(normTitle) >= 0) {
      score = 1;
    }

    var words = getSignificantWords(normTitle);
    var matchedWords = [];
    var compactTitle = getCompactTitle(normTitle);
    var compactFilename = collapseSpaces(normFilename);

    if (compactTitle.length >= 4 && compactFilename.indexOf(compactTitle) >= 0) {
      score = Math.max(score, 0.95);
    }

    if (words.length >= 2 && compactContainsWordSequence(compactFilename, words)) {
      score = Math.max(score, 0.92);
    }

    if (!words.length) {
      if (diag) {
        logMatchDiagnostic('scoreTitleAgainstFilename', {
          originalFilename: originalFilename,
          normalizedFilename: normFilename,
          normalizedTitle: normTitle,
          compactTitle: compactTitle,
          compactFilename: compactFilename,
          significantWords: words,
          matchedWords: matchedWords,
          finalScore: score,
          threshold: MIN_MATCH_SCORE
        });
      }
      return score;
    }

    var matched = 0;
    for (var i = 0; i < words.length; i++) {
      if (wordMatchesInFilename(normFilename, words[i])) {
        matched++;
        matchedWords.push(words[i]);
      }
    }

    var wordScore = matched / words.length;
    var minRequired = words.length >= 2 ? 2 : 1;

    if (matched < minRequired) {
      if (words.length >= 3 && matched >= words.length - 1) {
        wordScore = matched / words.length;
      } else {
        wordScore *= 0.35;
      }
    }

    score = Math.max(score, wordScore);

    if (words.length >= 2 && matched === 1 && score < 0.55) {
      score *= 0.5;
    }

    var finalScore = Math.min(1, score);

    if (diag) {
        logMatchDiagnostic('scoreTitleAgainstFilename', {
          originalFilename: originalFilename,
          normalizedFilename: normFilename,
          normalizedTitle: normTitle,
          compactTitle: compactTitle,
          compactFilename: compactFilename,
          significantWords: words,
          matchedWords: matchedWords,
          finalScore: finalScore,
          threshold: MIN_MATCH_SCORE
        });
    }

    return finalScore;
  }

  function scoreMovieAgainstFilename(movie, filename) {
    var normFilename = normalizeTitle(filename);
    if (!normFilename) return 0;

    var best = 0;
    var variants = movieTitleVariants(movie);
    var diag = isMatchDiagnosticFilename(filename) || isMatchDiagnosticFilename(normFilename);

    for (var i = 0; i < variants.length; i++) {
      best = Math.max(best, scoreTitleAgainstFilename(variants[i], normFilename, filename));
    }

    if (diag) {
      logMatchDiagnostic('scoreMovieAgainstFilename', {
        movieTitle: movie.title || movie.name || '',
        movieOriginalTitle: movie.original_title || movie.original_name || '',
        originalFilename: filename,
        normalizedFilename: normFilename,
        titleVariants: variants,
        bestScore: best,
        threshold: MIN_MATCH_SCORE,
        passesThreshold: best >= MIN_MATCH_SCORE
      });
    }

    return best;
  }

  function logMatchScore(movie, candidate, score) {
    console.log('[MATCH SCORE]');
    console.log('movie:', movie.title || movie.name || '');
    console.log('candidate:', candidate);
    console.log('score:', score);
  }

  function filterMagnetsForMovie(magnets, movie) {
    logMovieDataSources('filterMagnetsForMovie — start', movie);

    var list = toArray(magnets);
    var movieTitle = movie.title || movie.name || '';
    var originalTitle = movie.original_title || movie.original_name || '';
    var year = movie.release_date
      ? String(movie.release_date).slice(0, 4)
      : movie.first_air_date
        ? String(movie.first_air_date).slice(0, 4)
        : '';

    console.log('[STEP] filterMagnetsForMovie input count', list.length);
    console.log('[MATCH] movie.title', movieTitle);
    console.log('[MATCH] movie.original_title', originalTitle);
    console.log('[MATCH] movie.id', movie.id);
    console.log('[MATCH] movie.year', year);
    console.log('[PIPELINE] filterMagnetsForMovie — source length:', list.length);

    if (!Array.isArray(list)) {
      console.warn('[MATCH] abort: magnets is not an array after toArray');
      return [];
    }

    console.log('[CANDIDATE MATCH] filterMagnetsForMovie session', {
      movieTitle: movieTitle,
      normalizedMovieTitle: normalizeTitle(movieTitle),
      normalizedOriginalTitle: normalizeTitle(originalTitle),
      titleVariants: movieTitleVariants(movie),
      threshold: MIN_MATCH_SCORE,
      inputCount: list.length
    });

    var scored = [];

    for (var i = 0; i < list.length; i++) {
      var m = list[i];
      var filename =
        m && (m.filename || m.name || m.title || m.Title)
          ? m.filename || m.name || m.title || m.Title
          : '';
      var score = scoreMovieAgainstFilename(movie, filename);
      var matched = score >= MIN_MATCH_SCORE;

      logPerCandidateMatch(movie, filename, score);

      console.log('[MATCH CHECK]', {
        movieTitle: movieTitle,
        originalTitle: originalTitle,
        filename: filename,
        score: score,
        matched: matched
      });

      logMatchScore(movie, filename, score);

      if (matched) {
        m._matchScore = score;
        scored.push(m);
      }
    }

    scored.sort(function (a, b) {
      return (b._matchScore || 0) - (a._matchScore || 0);
    });

    console.log('[MATCH] matched count', scored.length);
    console.log('[PIPELINE] filterMagnetsForMovie — output length:', scored.length);
    console.log('[ACCOUNT MATCH] summary', {
      input: list.length,
      matched: scored.length,
      threshold: MIN_MATCH_SCORE,
      movieTitle: movieTitle
    });

    return scored;
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
    console.log('[AllDebrid] selected result', row);

    Lampa.Loading.start(function () {
      Lampa.Loading.stop();
    });

    resolveMagnetId(row)
      .then(function (magnetId) {
        return fetchMagnetFiles(magnetId);
      })
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

  function searchCachedTorrents(movie, searchId) {
    logMovieDataSources('searchCachedTorrents — start (account ready magnets only)', movie);

    var info = extractMovieInfo(movie);
    console.log('[PIPELINE] searchCachedTorrents — MVP account path searchId', searchId);
    console.log('[AllDebrid] search for', info);

    return searchAccountLibraryFallback(movie, searchId).then(function (results) {
      if (isSearchStale(searchId)) {
        console.log('[PIPELINE] searchCachedTorrents — stale search');
        return [];
      }

      console.log('[PIPELINE] searchCachedTorrents — account results', results.length);
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
      Lampa.Noty.show('No cached torrents found');
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
          handleResultSelect(row, movie, info);
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
      title: 'AllDebrid — ' + (resultsList.length === 1 ? '1 match' : resultsList.length + ' matches'),
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
    var activity = Lampa.Activity.active();
    console.log('[ACTIVE ACTIVITY]', activity);
    console.log('[ACTIVE MOVIE]', activity && activity.movie);
    console.log('[ACTIVE CARD]', activity && activity.card);

    var movieLive = getActiveMovie();
    logMovieDataSources('onAllDebridClick — live (before snapshot)', movieLive);

    if (!movieLive) {
      Lampa.Noty.show('No movie data');
      return;
    }

    var movie = snapshotMovie(movieLive);
    console.log('[SEARCH MOVIE]', movie);
    logMovieDataSources('onAllDebridClick — snapshot (passed to search)', movie);

    var searchId = ++activeSearchId;
    var info = extractMovieInfo(movie);

    console.log('[AllDebrid] movie (snapshot)', movie);
    console.log('[AllDebrid] movie info', info);
    console.log('[STEP] search started searchId', searchId, 'movie id', movie.id);

    if (!ensureApiKeyConfigured()) {
      return;
    }

    Lampa.Loading.start(function () {
      Lampa.Loading.stop();
    });

    searchCachedTorrents(movie, searchId)
      .then(function (results) {
        if (isSearchStale(searchId)) {
          console.log('[STEP] ignoring stale search results searchId', searchId);
          Lampa.Loading.stop();
          return;
        }

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

  function getMovieId(movie) {
    if (!movie || movie.id == null) return null;
    return String(movie.id);
  }

  function getMovieForButton(fullEvent) {
    if (fullEvent && fullEvent.data && fullEvent.data.movie) {
      return fullEvent.data.movie;
    }

    if (fullEvent && fullEvent.props && fullEvent.props.get) {
      try {
        var fromProps = fullEvent.props.get('movie');
        if (fromProps) return fromProps;
      } catch (err) {
        console.warn('[AllDebrid] props.get(movie) failed', err);
      }
    }

    return getActiveMovie();
  }

  function getMountKey(mount) {
    if (!mount || !mount.length) return null;

    var node = mount[0];
    var key = node.getAttribute('data-alldebrid-mount');

    if (!key) {
      key = 'ad-mount-' + Date.now() + '-' + Math.floor(Math.random() * 100000);
      node.setAttribute('data-alldebrid-mount', key);
    }

    return key;
  }

  function getVisibleButtonMount(fullEvent) {
    if (fullEvent && fullEvent.body && fullEvent.body.length) {
      var scoped = fullEvent.body.find('.full-start-new__buttons').first();
      if (scoped.length) return scoped;
    }

    var activity = Lampa.Activity.active();
    if (activity && activity.component === 'full' && activity.activity && activity.activity.render) {
      var render = activity.activity.render();
      if (render && render.length) {
        var activeMount = render.find('.full-start-new__buttons').first();
        if (activeMount.length) return activeMount;
      }
    }

    var picked = null;

    $('.full-start-new__buttons').each(function () {
      var el = $(this);
      if (!el.closest('.activity--active').length) return;
      picked = el;
      return false;
    });

    if (picked && picked.length) return picked;

    return $('.full-start-new__buttons').first();
  }

  function removeAllDebridButton(mount) {
    var removed = 0;

    if (mount && mount.length) {
      removed = mount.find('.button--alldebrid').length;
      mount.find('.button--alldebrid').off('hover:enter click').remove();
    } else {
      removed = $('.button--alldebrid').length;
      $('.button--alldebrid').off('hover:enter click').remove();
    }

    if (removed) {
      console.log('[BUTTON REMOVED]');
    }
  }

  function createAllDebridButton(mount, movie) {
    if (!mount || !mount.length) return false;
    if (mount.find('.button--alldebrid').length) return true;

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

    mount.find('.button--alldebrid').on('hover:enter click', onAllDebridClick);

    console.log('[BUTTON CREATED]');
    console.log('movie id', movie && movie.id);
    return true;
  }

  function syncAllDebridButton(fullEvent) {
    var activity = Lampa.Activity.active();
    var onFullCard = activity && activity.component === 'full';
    var movie = getMovieForButton(fullEvent);
    var movieId = getMovieId(movie);

    if (!onFullCard || !movie) {
      var active = Lampa.Activity.active();
      if (!active || active.component !== 'full') {
        if ($('.button--alldebrid').length) {
          removeAllDebridButton();
        }
        lastMovieId = null;
        lastMountKey = null;
      }
      return;
    }

    var mount = getVisibleButtonMount(fullEvent);
    if (!mount.length) return;

    var mountKey = getMountKey(mount);
    var buttonMissing = !mount.find('.button--alldebrid').length;
    var movieChanged = movieId !== lastMovieId;
    var mountChanged = mountKey !== lastMountKey;
    var needsUpdate = buttonMissing || movieChanged || mountChanged;

    if (!needsUpdate) return;

    if (!buttonMissing && (movieChanged || mountChanged)) {
      removeAllDebridButton(mount);
    }

    if (mount.find('.button--alldebrid').length) {
      lastMovieId = movieId;
      lastMountKey = mountKey;
      return;
    }

    if (movieChanged || mountChanged) {
      logMovieDataSources('syncAllDebridButton — injecting button for card', movie);
    }

    console.log('[BUTTON INIT]');
    console.log('movie id', movie.id);

    createAllDebridButton(mount, movie);
    lastMovieId = movieId;
    lastMountKey = mountKey;
  }

  function scheduleButtonSync(delay, fullEvent) {
    var timer = setTimeout(function () {
      syncAllDebridButton(fullEvent);
    }, delay);

    buttonSyncTimers.push(timer);
  }

  function installCardListeners() {
    if (!Lampa.Listener || !Lampa.Listener.follow) return;

    Lampa.Listener.follow('full', function (e) {
      if (!e) return;

      if (e.type === 'start') {
        var startMovie = e.data && e.data.movie;
        console.log('[BUTTON INIT]');
        console.log('movie id', startMovie && startMovie.id);
        scheduleButtonSync(0, e);
        scheduleButtonSync(150, e);
        scheduleButtonSync(400, e);
        scheduleButtonSync(900, e);
        return;
      }

      if (e.type === 'complite' || e.type === 'build') {
        scheduleButtonSync(0, e);
        scheduleButtonSync(200, e);
        scheduleButtonSync(600, e);
      }
    });

    Lampa.Listener.follow('activity', function (e) {
      if (!e || e.component !== 'full') return;

      if (e.type === 'destroy') {
        var active = Lampa.Activity.active();
        if (active && active.component === 'full') {
          scheduleButtonSync(100, null);
          scheduleButtonSync(400, null);
          return;
        }

        removeAllDebridButton();
        lastMovieId = null;
        lastMountKey = null;
        return;
      }

      if (e.type === 'start' || e.type === 'create' || e.type === 'init') {
        scheduleButtonSync(50, null);
        scheduleButtonSync(350, null);
      }
    });
  }

  function startPolling() {
    installCardListeners();
    syncAllDebridButton(null);
    if (pollTimer) clearInterval(pollTimer);
    pollTimer = setInterval(function () {
      syncAllDebridButton(null);
    }, POLL_MS);
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
