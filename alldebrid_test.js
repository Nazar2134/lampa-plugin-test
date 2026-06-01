(function () {
    'use strict';

    if (window.plugin_ad_test) return;
    window.plugin_ad_test = true;

    function init() {
        Lampa.Manifest.plugins = {
            type:      'other',
            version:   '1.0.0',
            name:      'AD Test',
            component: 'ad_test'
        };

        Lampa.Listener.follow('torrent', function (e) {
            console.log('[AD] event:', e.type);

            if (e.type !== 'onlong') return;

            var t = e.element;
            console.log('[AD] Title:',     t.Title);
            console.log('[AD] MagnetUri:', t.MagnetUri);

            e.menu.push({
                title: 'AllDebrid Test',
                onSelect: function () {
                    console.log('[AD] onSelect — Title:',     t.Title);
                    console.log('[AD] onSelect — MagnetUri:', t.MagnetUri);
                }
            });
        });

        console.log('[AD] plugin ready');
    }

    if (window.appready) {
        init();
    } else {
        Lampa.Listener.follow('app', function (e) {
            if (e.type === 'ready') init();
        });
    }

}());
