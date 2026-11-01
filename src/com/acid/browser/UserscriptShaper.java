package com.acid.browser;

import org.json.JSONObject;

import java.util.List;

/**
 * Shapes userscript source into a sandboxed, protected closure containing
 * GreaseMonkey / Tampermonkey compatibility shims ({@code unsafeWindow}, {@code GM_*},
 * and Promise-based {@code GM.*}), pre-bundled {@code @require} dependencies,
 * and re-entrance execution guards.
 *
 * <p>The per-process bridge token is embedded in the closure only. It is never published on
 * {@code window}, so page scripts cannot lift it from a global.
 */
public class UserscriptShaper {

    /** Convenience form without token; tests and legacy callers. */
    public static String shape(UserscriptMeta meta, List<String> requires) {
        return shape(meta, requires, null);
    }

    /**
     * Builds executable JavaScript code ready for {@code WebView.evaluateJavascript}.
     *
     * @param meta     userscript metadata and body
     * @param requires list of pre-fetched dependency script sources
     * @param secret   bridge token the native side expects on every call; may be null
     */
    public static String shape(UserscriptMeta meta, List<String> requires, String secret) {
        if (meta == null || meta.body == null || meta.body.trim().isEmpty()) {
            return "";
        }

        String scriptId = meta.fileName != null && !meta.fileName.isEmpty()
            ? meta.fileName
            : "custom_script";
        String guardKey = JSONObject.quote(scriptId);
        String safeScriptId = JSONObject.quote(scriptId);
        String safeSecret = JSONObject.quote(secret == null ? "" : secret);

        JSONObject gmInfo = new JSONObject();
        try {
            JSONObject scriptObj = new JSONObject();
            scriptObj.put("name", meta.name);
            scriptObj.put("namespace", meta.namespace);
            scriptObj.put("version", meta.version);
            scriptObj.put("description", meta.description);
            scriptObj.put("runAt", meta.runAt);
            org.json.JSONArray grantArr = new org.json.JSONArray();
            for (String g : meta.grants) grantArr.put(g);
            scriptObj.put("grant", grantArr);
            gmInfo.put("script", scriptObj);
            gmInfo.put("scriptHandler", "Acid");
            gmInfo.put("version", BuildInfo.VERSION_NAME);
        } catch (Exception ignored) {}
        String gmInfoJson = gmInfo.toString();

        StringBuilder sb = new StringBuilder();
        sb.append("(function(){\n");
        sb.append("try {\n");
        sb.append("  window.__acidInjectedUserscripts = window.__acidInjectedUserscripts || {};\n");
        sb.append("  if (window.__acidInjectedUserscripts[").append(guardKey).append("]) return;\n");
        sb.append("  window.__acidInjectedUserscripts[").append(guardKey).append("] = true;\n\n");

        sb.append("  window.__acid_gm_menus = window.__acid_gm_menus || {};\n");
        sb.append("  window.__acid_gm_menus[").append(safeScriptId).append("] = window.__acid_gm_menus[")
            .append(safeScriptId).append("] || {};\n");
        sb.append("  var _menus = window.__acid_gm_menus[").append(safeScriptId).append("];\n\n");

        sb.append("  var unsafeWindow = window;\n");
        sb.append("  try { window.wrappedJSObject = unsafeWindow; } catch(e){}\n\n");

        sb.append("  var _acidRand = function(len) {\n");
        sb.append("    var s = '', chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';\n");
        sb.append("    for (var i = 0; i < len; i++) s += chars.charAt(Math.floor(Math.random() * chars.length));\n");
        sb.append("    return s;\n");
        sb.append("  };\n\n");

        // The bridge object and token live only in this closure.
        sb.append("  var _host = window.").append(UserscriptNativeBridge.JS_INTERFACE_NAME).append(";\n");
        sb.append("  var _secret = ").append(safeSecret).append(";\n");
        sb.append("  var _sid = ").append(safeScriptId).append(";\n");
        sb.append("  var _stringify = JSON.stringify.bind(JSON), _parse = JSON.parse.bind(JSON);\n");
        sb.append("  var _callHost = function(act, obj) {\n");
        sb.append("    if (!_host || !_host.call) return '';\n");
        sb.append("    obj = obj || {}; obj.secret = _secret; obj.scriptId = _sid;\n");
        sb.append("    try { return _host.call(act, _stringify(obj)); } catch(e){ return ''; }\n");
        sb.append("  };\n");
        sb.append("  var _val = function(res, def) {\n");
        sb.append("    if (!res) return def;\n");
        sb.append("    try { var p = _parse(res); return p.value !== undefined && p.value !== null ? p.value : def; } catch(e){ return def; }\n");
        sb.append("  };\n\n");

        // ---- value storage ----
        sb.append("  var GM_setValue = function(key, val) { _callHost('setValue', { name: String(key), value: val }); };\n");
        sb.append("  var GM_getValue = function(key, defVal) { return _val(_callHost('getValue', { name: String(key), defaultValue: defVal }), defVal); };\n");
        sb.append("  var GM_deleteValue = function(key) { _callHost('deleteValue', { name: String(key) }); };\n");
        sb.append("  var GM_listValues = function() { try { return _parse(_callHost('listValues', {})) || []; } catch(e){ return []; } };\n");
        sb.append("  var GM_setValues = function(o) { if (o) for (var k in o) GM_setValue(k, o[k]); };\n");
        sb.append("  var GM_getValues = function(keys) {\n");
        sb.append("    var r = {}; if (!keys) return r;\n");
        sb.append("    if (Array.isArray(keys)) keys.forEach(function(k){ r[k] = GM_getValue(k); });\n");
        sb.append("    else for (var k in keys) r[k] = GM_getValue(k, keys[k]);\n");
        sb.append("    return r;\n");
        sb.append("  };\n");
        sb.append("  var GM_deleteValues = function(keys) { if (keys && keys.length) for (var i = 0; i < keys.length; i++) GM_deleteValue(keys[i]); };\n\n");

        // ---- value change listeners (pushed from native through a per-script hook) ----
        sb.append("  var _listeners = {}, _lid = 0;\n");
        sb.append("  var GM_addValueChangeListener = function(name, cb) {\n");
        sb.append("    if (typeof cb !== 'function') return 0;\n");
        sb.append("    var id = ++_lid; _listeners[id] = { name: String(name), cb: cb }; return id;\n");
        sb.append("  };\n");
        sb.append("  var GM_removeValueChangeListener = function(id) { delete _listeners[id]; };\n");
        sb.append("  window.__acid_gm_vc = window.__acid_gm_vc || {};\n");
        sb.append("  window.__acid_gm_vc[_sid] = function(name, oldV, newV, remote) {\n");
        sb.append("    for (var id in _listeners) {\n");
        sb.append("      var l = _listeners[id];\n");
        sb.append("      if (l.name === name) { try { l.cb(name, oldV, newV, !!remote); } catch(e){ console.error(e); } }\n");
        sb.append("    }\n");
        sb.append("  };\n\n");

        // ---- DOM ----
        sb.append("  var GM_addStyle = function(css) {\n");
        sb.append("    if (!css) return null;\n");
        sb.append("    var h = document.head || document.getElementsByTagName('head')[0] || document.documentElement;\n");
        sb.append("    if (!h) return null;\n");
        sb.append("    var st = document.createElement('style');\n");
        sb.append("    st.type = 'text/css'; st.textContent = css; h.appendChild(st);\n");
        sb.append("    return st;\n");
        sb.append("  };\n");
        sb.append("  var GM_addElement = function(parent, tag, attrs) {\n");
        sb.append("    if (typeof parent === 'string') { attrs = tag; tag = parent; parent = undefined; }\n");
        sb.append("    var el = document.createElement(tag || 'div');\n");
        sb.append("    if (attrs) {\n");
        sb.append("      for (var k in attrs) {\n");
        sb.append("        if (k === 'textContent') { el.textContent = attrs[k]; }\n");
        sb.append("        else { try { el.setAttribute(k, attrs[k]); } catch(e){} }\n");
        sb.append("      }\n");
        sb.append("    }\n");
        sb.append("    var p = parent || document.head || document.body || document.documentElement;\n");
        sb.append("    if (p) p.appendChild(el);\n");
        sb.append("    return el;\n");
        sb.append("  };\n\n");

        // ---- system ----
        sb.append("  var GM_setClipboard = function(data) { _callHost('setClipboard', { data: String(data || '') }); };\n");
        sb.append("  var GM_log = function() { _callHost('log', { message: Array.prototype.join.call(arguments, ' ') }); };\n");
        sb.append("  var GM_notification = function(a, b, c, d) {\n");
        sb.append("    var o = (typeof a === 'object' && a) ? a : { text: a, title: b, image: c, onclick: d };\n");
        sb.append("    _callHost('notification', { title: String(o.title || ''), text: String(o.text || '') });\n");
        sb.append("  };\n");
        sb.append("  var GM_openInTab = function(url, opts) {\n");
        sb.append("    var active = true;\n");
        sb.append("    if (typeof opts === 'boolean') active = !opts; else if (opts && opts.active === false) active = false;\n");
        sb.append("    else if (opts && typeof opts.background === 'boolean') active = !opts.background;\n");
        sb.append("    _callHost('openInTab', { url: String(url), active: active });\n");
        sb.append("    return { close: function(){}, closed: false, onclose: null };\n");
        sb.append("  };\n");
        sb.append("  var GM_download = function(url, name) {\n");
        sb.append("    if (url && typeof url === 'object') { name = url.name; url = url.url; }\n");
        sb.append("    _callHost('download', { url: String(url), name: name ? String(name) : '' });\n");
        sb.append("  };\n");
        sb.append("  var GM_getResourceText = function(n) { return _val(_callHost('getResourceText', { resource: String(n) }), null); };\n");
        sb.append("  var GM_getResourceURL = function(n) { return _val(_callHost('getResourceURL', { resource: String(n) }), null); };\n");

        // ---- menu ----
        sb.append("  var GM_registerMenuCommand = function(name, fn) {\n");
        sb.append("    if (!name || typeof fn !== 'function') return name;\n");
        sb.append("    _menus[name] = fn; return name;\n");
        sb.append("  };\n");
        sb.append("  var GM_unregisterMenuCommand = function(name) { if (name) delete _menus[name]; };\n\n");

        sb.append("  var GM_info = ").append(gmInfoJson).append(";\n\n");

        // ---- network ----
        sb.append("  var GM_xmlhttpRequest = function(details) {\n");
        sb.append("    if (!details || !details.url) return { abort: function(){} };\n");
        sb.append("    var id = '_acid_xhr_' + _acidRand(10);\n");
        sb.append("    var names = ['onload','onerror','ontimeout','onabort','onprogress','onloadstart','onreadystatechange'];\n");
        sb.append("    var req = {\n");
        sb.append("      id: id, url: String(details.url),\n");
        sb.append("      method: (details.method || 'GET').toUpperCase(),\n");
        sb.append("      headers: details.headers || {}, data: null,\n");
        sb.append("      timeout: details.timeout || 15000,\n");
        sb.append("      responseType: details.responseType || 'text',\n");
        sb.append("      v_ua: navigator.userAgent\n");
        sb.append("    };\n");
        sb.append("    var d = details.data;\n");
        sb.append("    if (typeof FormData !== 'undefined' && d instanceof FormData) d = new URLSearchParams(d).toString();\n");
        sb.append("    else if (typeof URLSearchParams !== 'undefined' && d instanceof URLSearchParams) d = d.toString();\n");
        sb.append("    else if (Array.isArray(d)) d = d.join(',');\n");
        sb.append("    else if (d !== undefined && d !== null && typeof d === 'object') d = _stringify(d);\n");
        sb.append("    req.data = (d === undefined || d === null) ? null : String(d);\n");
        sb.append("    names.forEach(function(n) {\n");
        sb.append("      var fn = details[n];\n");
        sb.append("      if (typeof fn === 'function') {\n");
        sb.append("        var key = id + '_' + n;\n");
        sb.append("        window[key] = function(r) { try { if (r) r.context = details.context; fn(r); } catch(e){ console.error(e); } };\n");
        sb.append("        req[n] = key;\n");
        sb.append("      }\n");
        sb.append("    });\n");
        sb.append("    _callHost('xmlhttpRequest', req);\n");
        sb.append("    return { abort: function() { _callHost('abortRequest', { id: id }); } };\n");
        sb.append("  };\n");
        sb.append("  GM_xmlhttpRequest.UNSENT = 0; GM_xmlhttpRequest.OPENED = 1;\n");
        sb.append("  GM_xmlhttpRequest.HEADERS_RECEIVED = 2; GM_xmlhttpRequest.LOADING = 3; GM_xmlhttpRequest.DONE = 4;\n\n");

        // ---- GM.* promise API ----
        sb.append("  var _p = function(f) { return function() { var a = arguments; return new Promise(function(res){ res(f.apply(null, a)); }); }; };\n");
        sb.append("  var GM = {\n");
        sb.append("    info: GM_info,\n");
        sb.append("    setValue: _p(GM_setValue), getValue: _p(GM_getValue), deleteValue: _p(GM_deleteValue),\n");
        sb.append("    listValues: _p(GM_listValues), setValues: _p(GM_setValues), getValues: _p(GM_getValues),\n");
        sb.append("    deleteValues: _p(GM_deleteValues), addStyle: _p(GM_addStyle), addElement: _p(GM_addElement),\n");
        sb.append("    setClipboard: _p(GM_setClipboard), notification: _p(GM_notification),\n");
        sb.append("    openInTab: _p(GM_openInTab), download: _p(GM_download),\n");
        sb.append("    getResourceText: _p(GM_getResourceText), getResourceUrl: _p(GM_getResourceURL),\n");
        sb.append("    getResourceURL: _p(GM_getResourceURL),\n");
        sb.append("    registerMenuCommand: _p(GM_registerMenuCommand), unregisterMenuCommand: _p(GM_unregisterMenuCommand),\n");
        sb.append("    xmlHttpRequest: function(d) {\n");
        sb.append("      var h;\n");
        sb.append("      var p = new Promise(function(resolve, reject) {\n");
        sb.append("        var c = Object.assign({}, d);\n");
        sb.append("        c.onload = function(r) { resolve(r); if (d.onload) d.onload(r); };\n");
        sb.append("        c.onerror = function(e) { reject(e); if (d.onerror) d.onerror(e); };\n");
        sb.append("        c.ontimeout = function(e) { reject(e); if (d.ontimeout) d.ontimeout(e); };\n");
        sb.append("        c.onabort = function(e) { reject(e); if (d.onabort) d.onabort(e); };\n");
        sb.append("        h = GM_xmlhttpRequest(c);\n");
        sb.append("      });\n");
        sb.append("      p.abort = function() { if (h) h.abort(); };\n");
        sb.append("      return p;\n");
        sb.append("    }\n");
        sb.append("  };\n\n");

        // ---- Soft sandbox: only granted APIs are visible to the script ----
        String[] apis = {
            "GM_setValue", "GM_getValue", "GM_deleteValue", "GM_listValues",
            "GM_setValues", "GM_getValues", "GM_deleteValues",
            "GM_addValueChangeListener", "GM_removeValueChangeListener",
            "GM_addStyle", "GM_addElement", "GM_setClipboard", "GM_log", "GM_notification",
            "GM_openInTab", "GM_download", "GM_getResourceText", "GM_getResourceURL",
            "GM_registerMenuCommand", "GM_unregisterMenuCommand", "GM_xmlhttpRequest"
        };
        StringBuilder params = new StringBuilder("window, unsafeWindow, GM, GM_info");
        StringBuilder argsOut = new StringBuilder("unsafeWindow, unsafeWindow, GM, GM_info");
        for (String api : apis) {
            params.append(", ").append(api);
            argsOut.append(", ").append(grantedExpr(meta, api));
        }

        if (meta.unwrap) {
            // @unwrap: expose the granted APIs on window, then run the body at page scope so
            // its top-level vars/functions leak like a normal <script>. The token and all
            // shim internals stay inside this closure and are never published.
            sb.append("  window.__acid_expose_unwrapped = function() {\n");
            for (String api : apis) {
                if (meta.isGranted(api)) {
                    sb.append("    try { window.").append(api).append(" = ").append(api).append("; } catch(e){}\n");
                }
            }
            sb.append("    try { window.GM = GM; window.GM_info = GM_info;"
                + " window.unsafeWindow = window; window.wrappedJSObject = window; } catch(e){}\n");
            sb.append("  };\n");
            sb.append("  window.__acid_expose_unwrapped();\n");
            sb.append("  try { delete window.__acid_expose_unwrapped; } catch(e){}\n");
            sb.append("} catch(e) {\n");
            sb.append("  console.error('[AcidUserscriptError in ")
                .append(scriptId.replace("'", "\\'")).append("]', e);\n");
            sb.append("}\n})();\n");

            sb.append("/* === @require dependencies === */\n");
            if (requires != null) {
                for (String dep : requires) {
                    if (dep != null && !dep.trim().isEmpty()) {
                        sb.append("try {\n").append(dep).append("\n} catch(e) {"
                            + " console.error('[AcidUserscriptRequireError]', e); }\n");
                    }
                }
            }
            sb.append("/* === Script Body (@unwrap: page scope) === */\n");
            sb.append(meta.body).append("\n");
            return sb.toString();
        }

        sb.append("  (function(").append(params).append(") {\n");

        if (requires != null && !requires.isEmpty()) {
            sb.append("\n    /* === Pre-bundled @require Dependencies === */\n");
            for (String dep : requires) {
                if (dep != null && !dep.trim().isEmpty()) {
                    sb.append("    try {\n");
                    sb.append(dep).append("\n");
                    sb.append("    } catch(e) { console.error('[AcidUserscriptRequireError]', e); }\n");
                }
            }
        }

        sb.append("\n    /* === Script Body === */\n");
        sb.append("    ").append(meta.body).append("\n");

        sb.append("  })(").append(argsOut).append(");\n");

        sb.append("} catch(e) {\n");
        sb.append("  console.error('[AcidUserscriptError in ").append(scriptId.replace("'", "\\'")).append("]', e);\n");
        sb.append("}\n");
        sb.append("})();");

        return sb.toString();
    }


    /**
     * Bootstrap injected at the earliest possible moment on every page.
     *
     * <p>The page tells us when it actually reached {@code document-start} (head exists),
     * {@code document-end} (DOMContentLoaded) and {@code document-idle} (load), instead of the
     * native side guessing from load progress. Phases are 1/2/4 and are idempotent.
     */
    public static String bootstrap(String secret) {
        String sec = JSONObject.quote(secret == null ? "" : secret);
        StringBuilder sb = new StringBuilder();
        sb.append("(function(){\n");
        sb.append("  if (window.__acid_bootstrapped) return;\n");
        sb.append("  window.__acid_bootstrapped = true;\n");
        sb.append("  var SECRET = ").append(sec).append(";\n");
        sb.append("  var fired = {};\n");
        sb.append("  function req(phase) {\n");
        sb.append("    if (fired[phase]) return; fired[phase] = 1;\n");
        sb.append("    var host = window.").append(UserscriptNativeBridge.JS_INTERFACE_NAME).append(";\n");
        sb.append("    if (!host || !host.call) return;\n");
        sb.append("    try { host.call('requestInjection', JSON.stringify({ secret: SECRET, runAt: phase })); } catch(e){}\n");
        sb.append("  }\n");
        sb.append("  function headReady() {\n");
        sb.append("    if (document.head) { req(1); return true; }\n");
        sb.append("    return false;\n");
        sb.append("  }\n");
        sb.append("  try {\n");
        sb.append("    if (!headReady()) {\n");
        sb.append("      var mo = new MutationObserver(function(){ if (headReady()) mo.disconnect(); });\n");
        sb.append("      mo.observe(document.documentElement || document, { childList: true, subtree: true });\n");
        sb.append("    }\n");
        sb.append("  } catch(e) {}\n");
        sb.append("  try {\n");
        sb.append("    if (document.readyState === 'loading') {\n");
        sb.append("      document.addEventListener('DOMContentLoaded', function(){ req(2); });\n");
        sb.append("    } else { req(2); }\n");
        sb.append("  } catch(e) {}\n");
        sb.append("  try {\n");
        sb.append("    if (document.readyState === 'complete') { req(4); }\n");
        sb.append("    else { window.addEventListener('load', function(){ req(4); }); }\n");
        sb.append("  } catch(e) {}\n");
        sb.append("})();");
        return sb.toString();
    }

    /**
     * Minimal {@code window.external.Tampermonkey} facade so script sites (GreasyFork and
     * friends) show "installed" state and offer a one-tap install. Only injected on those
     * hosts, mirroring Via's approach.
     */
    public static String tampermonkeyFacade(String secret) {
        String sec = JSONObject.quote(secret == null ? "" : secret);
        StringBuilder sb = new StringBuilder();
        sb.append("(function(){\n");
        sb.append("  if (window.__acid_tm_facade) return;\n");
        sb.append("  window.__acid_tm_facade = true;\n");
        sb.append("  var SECRET = ").append(sec).append(";\n");
        sb.append("  var host = window.").append(UserscriptNativeBridge.JS_INTERFACE_NAME).append(";\n");
        sb.append("  function installed(name, ns) {\n");
        sb.append("    if (!host || !host.call) return false;\n");
        sb.append("    try {\n");
        sb.append("      var r = JSON.parse(host.call('isInstalled', JSON.stringify({"
            + " secret: SECRET, name: String(name || ''), namespace: String(ns || '') })));\n");
        sb.append("      return !!(r && r.value);\n");
        sb.append("    } catch(e) { return false; }\n");
        sb.append("  }\n");
        sb.append("  try {\n");
        sb.append("    window.external = window.external || {};\n");
        sb.append("    window.external.Tampermonkey = {\n");
        sb.append("      getVersion: function(cb) { if (typeof cb === 'function') cb({ version: '4.19.0', id: 'acid' }); },\n");
        sb.append("      isInstalled: function(name, ns, cb) {\n");
        sb.append("        var v = installed(name, ns);\n");
        sb.append("        if (typeof cb === 'function') cb(v);\n");
        sb.append("        return v;\n");
        sb.append("      },\n");
        sb.append("      openOptions: function(name, ns) {\n");
        sb.append("        try { if (window.__acid_open_options) window.__acid_open_options(String(name||''), String(ns||'')); } catch(e){}\n");
        sb.append("      }\n");
        sb.append("    };\n");
        sb.append("  } catch(e) {}\n");
        sb.append("})();");
        return sb.toString();
    }

    private static String grantedExpr(UserscriptMeta meta, String api) {
        return meta.isGranted(api) ? api : "undefined";
    }
}
