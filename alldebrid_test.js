(function () {
    'use strict';

    // Double-load guard
    if (window.plugin_ad_test) return;
    window.plugin_ad_test = true;

    function init() {
        // Register manifest so Lampa recognises this as a valid plugin
        Lampa.Manifest.plugins = {
            type:      'other',
            version:   '1.0.0',
            name:      'AD Test',
            component: 'ad_test'
        };

        console.log('AD PLUGIN LOADED');
    }

    // Respect startup order: run immediately if Lampa is already ready,
    // otherwise wait for the 'ready' event
    if (window.appready) {
        init();
    } else {
        Lampa.Listener.follow('app', function (e) {
            if (e.type === 'ready') init();
        });
    }

}());
