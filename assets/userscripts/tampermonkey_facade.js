(function(){
  if (window.__acid_tm_facade) return;
  window.__acid_tm_facade = true;
  var host = window.__acid_gm_host__;
  var _safeStr = (function(){
    try {
      var desc = Object.getOwnPropertyDescriptor(JSON, 'stringify');
      if (desc && typeof desc.value === 'function') return desc.value;
    } catch(e) {}
    return JSON.stringify;
  })();
  function installed(name, ns) {
    if (!host || !host.call) return false;
    try {
      var r = JSON.parse(host.call('isInstalled', _safeStr({
        name: String(name || ''), namespace: String(ns || '') })));
      return !!(r && r.value);
    } catch(e) { return false; }
  }
  try {
    window.external = window.external || {};
    window.external.Tampermonkey = {
      getVersion: function(cb) { if (typeof cb === 'function') cb({ version: '4.19.0', id: 'acid' }); },
      isInstalled: function(name, ns, cb) {
        var v = installed(name, ns);
        if (typeof cb === 'function') cb(v);
        return v;
      },
      openOptions: function(name, ns) {
        try { if (window.__acid_open_options) window.__acid_open_options(String(name||''), String(ns||'')); } catch(e){}
      }
    };
  } catch(e) {}
})();
