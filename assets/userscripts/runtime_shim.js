(function(){
try {
  window.__acidInjectedUserscripts = window.__acidInjectedUserscripts || {};
  if (window.__acidInjectedUserscripts[/*__GUARD_KEY__*/]) return;
  window.__acidInjectedUserscripts[/*__GUARD_KEY__*/] = true;

  window.__acid_gm_menus = window.__acid_gm_menus || {};
  window.__acid_gm_menus[/*__SAFE_SID__*/] = window.__acid_gm_menus[/*__SAFE_SID__*/] || {};
  var _menus = window.__acid_gm_menus[/*__SAFE_SID__*/];

  var unsafeWindow = window;
  try { window.wrappedJSObject = unsafeWindow; } catch(e){}

  var _acidRand = function(len) {
    var s = '', chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
    for (var i = 0; i < len; i++) s += chars.charAt(Math.floor(Math.random() * chars.length));
    return s;
  };

  var _host = window.__acid_gm_host__;
  var _secret = /*__SAFE_SECRET__*/"";
  var _sid = /*__SAFE_SID__*/"";
  var _stringify = JSON.stringify.bind(JSON), _parse = JSON.parse.bind(JSON);
  var _defProp = Object.defineProperty;
  var _callHost = function(act, obj) {
    if (!_host || !_host.call) return '';
    obj = obj || {};
    _defProp(obj, 'toJSON', { value: undefined, enumerable: false, configurable: true, writable: true });
    _defProp(obj, 'secret', { value: _secret, enumerable: true, configurable: true, writable: true });
    _defProp(obj, 'scriptId', { value: _sid, enumerable: true, configurable: true, writable: true });
    try { return _host.call(act, _stringify(obj)); } catch(e){ return ''; }
  };
  var _val = function(res, def) {
    if (!res) return def;
    try { var p = _parse(res); return p.value !== undefined && p.value !== null ? p.value : def; } catch(e){ return def; }
  };

  // ---- value storage ----
  var GM_setValue = function(key, val) { _callHost('setValue', { name: String(key), value: val }); };
  var GM_getValue = function(key, defVal) { return _val(_callHost('getValue', { name: String(key), defaultValue: defVal }), defVal); };
  var GM_deleteValue = function(key) { _callHost('deleteValue', { name: String(key) }); };
  var GM_listValues = function() { try { return _parse(_callHost('listValues', {})) || []; } catch(e){ return []; } };
  var GM_setValues = function(o) { if (o) for (var k in o) GM_setValue(k, o[k]); };
  var GM_getValues = function(keys) {
    var r = {}; if (!keys) return r;
    if (Array.isArray(keys)) keys.forEach(function(k){ r[k] = GM_getValue(k); });
    else for (var k in keys) r[k] = GM_getValue(k, keys[k]);
    return r;
  };
  var GM_deleteValues = function(keys) { if (keys && keys.length) for (var i = 0; i < keys.length; i++) GM_deleteValue(keys[i]); };

  // ---- value change listeners ----
  var _listeners = {}, _lid = 0;
  var GM_addValueChangeListener = function(name, cb) {
    if (typeof cb !== 'function') return 0;
    var id = ++_lid; _listeners[id] = { name: String(name), cb: cb }; return id;
  };
  var GM_removeValueChangeListener = function(id) { delete _listeners[id]; };
  window.__acid_gm_vc = window.__acid_gm_vc || {};
  window.__acid_gm_vc[_sid] = function(name, oldV, newV, remote) {
    for (var id in _listeners) {
      var l = _listeners[id];
      if (l.name === name) { try { l.cb(name, oldV, newV, !!remote); } catch(e){ console.error(e); } }
    }
  };

  // ---- DOM ----
  var GM_addStyle = function(css) {
    if (!css) return null;
    var h = document.head || document.getElementsByTagName('head')[0] || document.documentElement;
    if (!h) return null;
    var st = document.createElement('style');
    st.type = 'text/css'; st.textContent = css; h.appendChild(st);
    return st;
  };
  var GM_addElement = function(parent, tag, attrs) {
    if (typeof parent === 'string') { attrs = tag; tag = parent; parent = undefined; }
    var el = document.createElement(tag || 'div');
    if (attrs) {
      for (var k in attrs) {
        if (k === 'textContent') el.textContent = attrs[k];
        else el.setAttribute(k, attrs[k]);
      }
    }
    (parent || document.body || document.documentElement).appendChild(el);
    return el;
  };

  // ---- system ----
  var GM_setClipboard = function(data) { _callHost('setClipboard', { data: String(data || '') }); };
  var GM_log = function() { _callHost('log', { message: Array.prototype.join.call(arguments, ' ') }); };
  var GM_notification = function(a, b, c, d) {
    var o = (typeof a === 'object' && a) ? a : { text: a, title: b, image: c, onclick: d };
    _callHost('notification', { title: String(o.title || ''), text: String(o.text || '') });
  };
  var GM_openInTab = function(url, opts) {
    var active = true;
    if (typeof opts === 'boolean') active = !opts; else if (opts && opts.active === false) active = false;
    else if (opts && typeof opts.background === 'boolean') active = !opts.background;
    _callHost('openInTab', { url: String(url), active: active });
    return { close: function(){}, closed: false, onclose: null };
  };
  var GM_download = function(url, name) {
    if (url && typeof url === 'object') { name = url.name; url = url.url; }
    _callHost('download', { url: String(url), name: name ? String(name) : '' });
  };
  var GM_getResourceText = function(n) { return _val(_callHost('getResourceText', { resource: String(n) }), null); };
  var GM_getResourceURL = function(n) { return _val(_callHost('getResourceURL', { resource: String(n) }), null); };

  // ---- menu ----
  var GM_registerMenuCommand = function(name, fn) {
    if (!name || typeof fn !== 'function') return name;
    _menus[name] = fn; return name;
  };
  var GM_unregisterMenuCommand = function(name) { if (name) delete _menus[name]; };

  var GM_info = /*__GM_INFO__*/{};

  // ---- network ----
  var GM_xmlhttpRequest = function(details) {
    if (!details || !details.url) return { abort: function(){} };
    var id = '_acid_xhr_' + _acidRand(10);
    var names = ['onload','onerror','ontimeout','onabort','onprogress','onloadstart','onreadystatechange'];
    var cleaned = false;
    var cleanTimer = null;
    var _clean = function() {
      if (cleaned) return;
      cleaned = true;
      if (cleanTimer) {
        try { clearTimeout(cleanTimer); } catch(e){}
        cleanTimer = null;
      }
      names.forEach(function(n) { try { delete window[id + '_' + n]; } catch(e){} });
    };
    var req = {
      id: id, url: String(details.url),
      method: (details.method || 'GET').toUpperCase(),
      headers: details.headers || {}, data: null,
      timeout: details.timeout || 15000,
      responseType: details.responseType || 'text',
      v_ua: navigator.userAgent
    };
    var d = details.data;
    if (typeof FormData !== 'undefined' && d instanceof FormData) d = new URLSearchParams(d).toString();
    else if (typeof URLSearchParams !== 'undefined' && d instanceof URLSearchParams) d = d.toString();
    else if (Array.isArray(d)) d = d.join(',');
    else if (d !== undefined && d !== null && typeof d === 'object') d = _stringify(d);
    req.data = (d === undefined || d === null) ? null : String(d);
    names.forEach(function(n) {
      var fn = details[n];
      if (typeof fn === 'function') {
        var key = id + '_' + n;
        window[key] = function(r) {
          try { if (r) r.context = details.context; fn(r); } catch(e){ console.error(e); }
          finally {
            if (n !== 'onloadstart' && n !== 'onreadystatechange' && n !== 'onprogress') _clean();
          }
        };
        req[n] = key;
      }
    });
    _callHost('xmlhttpRequest', req);
    return {
      abort: function() {
        if (typeof details.onabort !== 'function') {
          _clean();
        } else {
          // Keep onabort registered so host-dispatched abort reaches details.onabort before cleanup.
          names.forEach(function(n) {
            if (n !== 'onabort') { try { delete window[id + '_' + n]; } catch(e){} }
          });
          if (!cleaned && !cleanTimer) {
            cleanTimer = setTimeout(_clean, 1500);
          }
        }
        _callHost('abortRequest', { id: id });
      }
    };
  };
  GM_xmlhttpRequest.UNSENT = 0; GM_xmlhttpRequest.OPENED = 1;
  GM_xmlhttpRequest.HEADERS_RECEIVED = 2; GM_xmlhttpRequest.LOADING = 3; GM_xmlhttpRequest.DONE = 4;

  var _dispatchHttpCb = function(name, obj, last) {
    try {
      if (obj && obj.responseType) {
        var type = obj.responseType;
        if (type === 'json') {
          try { obj.response = JSON.parse(obj.responseText); } catch(e){ obj.response = null; }
        } else if (type === 'document') {
          try { obj.response = new DOMParser().parseFromString(obj.responseText, 'text/html'); } catch(e){}
        } else if (type === 'blob' || type === 'arraybuffer' || type === 'stream') {
          if (obj.responseDataUrl) {
            var parts = obj.responseDataUrl.split(',');
            var mimeMatch = parts[0].match(/data:([^;]+)/);
            var mime = mimeMatch ? mimeMatch[1] : 'application/octet-stream';
            var decoded = atob(parts[1]);
            var bytes = new Uint8Array(decoded.length);
            for (var i = 0; i < decoded.length; i++) { bytes[i] = decoded.charCodeAt(i); }
            if (type === 'blob') {
              obj.response = new Blob([bytes], { type: mime });
            } else if (type === 'stream') {
              obj.response = new ReadableStream({ start: function(c) { c.enqueue(bytes); c.close(); } });
            } else {
              obj.response = bytes.buffer;
            }
            delete obj.responseDataUrl;
          }
        } else {
          obj.response = obj.responseText;
        }
      }
      var fn = window[name];
      if (typeof fn === 'function') {
        if (last) {
          delete window[name];
        }
        fn(obj);
      }
    } catch(e) { console.error('[AcidUserscriptHttpCallbackError]', e); }
  };
  try {
    var _dDesc = Object.getOwnPropertyDescriptor(window, '__acid_dispatch_http_cb');
    if (!_dDesc || _dDesc.configurable) {
      _defProp(window, '__acid_dispatch_http_cb', {
        value: _dispatchHttpCb, writable: false, configurable: false, enumerable: false
      });
    } else if (_dDesc.writable) {
      window.__acid_dispatch_http_cb = _dispatchHttpCb;
    }
  } catch(e) {
    try { window.__acid_dispatch_http_cb = _dispatchHttpCb; } catch(e2){}
  }

  // ---- GM.* promise API (filtered by @grant) ----
  var _rawGrants = (GM_info && GM_info.script && Array.isArray(GM_info.script.grant)) ? GM_info.script.grant : [];
  var _hasExplicitGrants = _rawGrants.length > 0;
  var _normGrant = function(g) {
    var s = String(g || '').trim().toLowerCase();
    return s.indexOf('gm.') === 0 ? ('gm_' + s.substring(3)) : s;
  };
  var _grantSet = {};
  for (var _gi = 0; _gi < _rawGrants.length; _gi++) {
    _grantSet[_normGrant(_rawGrants[_gi])] = true;
  }
  var _isGmGranted = function() {
    if (!_hasExplicitGrants) return true;
    for (var _i = 0; _i < arguments.length; _i++) {
      var _w = _normGrant(arguments[_i]);
      if (_w !== 'none' && _grantSet[_w]) return true;
    }
    return false;
  };
  var _p = function(f) { return function() { var a = arguments; return new Promise(function(res){ res(f.apply(null, a)); }); }; };
  var GM = { info: GM_info };
  if (_isGmGranted('GM_setValue', 'GM_setValues')) GM.setValue = _p(GM_setValue);
  if (_isGmGranted('GM_getValue', 'GM_getValues')) GM.getValue = _p(GM_getValue);
  if (_isGmGranted('GM_deleteValue', 'GM_deleteValues')) GM.deleteValue = _p(GM_deleteValue);
  if (_isGmGranted('GM_listValues')) GM.listValues = _p(GM_listValues);
  if (_isGmGranted('GM_setValues')) GM.setValues = _p(GM_setValues);
  if (_isGmGranted('GM_getValues')) GM.getValues = _p(GM_getValues);
  if (_isGmGranted('GM_deleteValues')) GM.deleteValues = _p(GM_deleteValues);
  if (_isGmGranted('GM_addStyle')) GM.addStyle = _p(GM_addStyle);
  if (_isGmGranted('GM_addElement')) GM.addElement = _p(GM_addElement);
  if (_isGmGranted('GM_setClipboard')) GM.setClipboard = _p(GM_setClipboard);
  if (_isGmGranted('GM_notification')) GM.notification = _p(GM_notification);
  if (_isGmGranted('GM_openInTab')) GM.openInTab = _p(GM_openInTab);
  if (_isGmGranted('GM_download')) GM.download = _p(GM_download);
  if (_isGmGranted('GM_getResourceText')) GM.getResourceText = _p(GM_getResourceText);
  if (_isGmGranted('GM_getResourceURL')) {
    GM.getResourceUrl = _p(GM_getResourceURL);
    GM.getResourceURL = _p(GM_getResourceURL);
  }
  if (_isGmGranted('GM_registerMenuCommand')) GM.registerMenuCommand = _p(GM_registerMenuCommand);
  if (_isGmGranted('GM_unregisterMenuCommand')) GM.unregisterMenuCommand = _p(GM_unregisterMenuCommand);
  if (_isGmGranted('GM_xmlhttpRequest')) {
    GM.xmlHttpRequest = function(d) {
      var h;
      var p = new Promise(function(resolve, reject) {
        var c = Object.assign({}, d);
        c.onload = function(r) { resolve(r); if (d.onload) d.onload(r); };
        c.onerror = function(e) { reject(e); if (d.onerror) d.onerror(e); };
        c.ontimeout = function(e) { reject(e); if (d.ontimeout) d.ontimeout(e); };
        c.onabort = function(e) { reject(e); if (d.onabort) d.onabort(e); };
        h = GM_xmlhttpRequest(c);
      });
      p.abort = function() { if (h) h.abort(); };
      return p;
    };
  }
