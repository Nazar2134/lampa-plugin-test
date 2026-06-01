(function () {
  'use strict';

  if (window.plugin_alldebrid_test) return;
  window.plugin_alldebrid_test = true;

  var POLL_MS = 1000;
  var buttonAdded = false;
  var pollTimer = null;

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
