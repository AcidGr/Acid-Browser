package com.acid.browser;

import org.json.JSONObject;

import java.util.List;

/**
 * Shapes userscript source into a sandboxed, protected closure containing
 * GreaseMonkey / Tampermonkey compatibility shims ({@code unsafeWindow}, {@code GM_*},
 * and Promise-based {@code GM.*}), pre-bundled {@code @require} dependencies,
 * and re-entrance execution guards.
 */
public class UserscriptShaper {

    /**
     * Builds executable JavaScript code ready for {@code WebView.evaluateJavascript}.
     *
     * @param meta userscript metadata and body
     * @param requires list of pre-fetched dependency script sources
     * @return shaped JavaScript string
     */
    public static String shape(UserscriptMeta meta, List<String> requires) {
        if (meta == null || meta.body == null || meta.body.trim().isEmpty()) {
            return "";
        }

        String scriptId = meta.fileName != null && !meta.fileName.isEmpty()
            ? meta.fileName
            : "custom_script";
        String guardKey = JSONObject.quote(scriptId);
        String safeScriptId = JSONObject.quote(scriptId);

        // Build GM_info JSON
        JSONObject gmInfo = new JSONObject();
        try {
            JSONObject scriptObj = new JSONObject();
            scriptObj.put("name", meta.name);
            scriptObj.put("namespace", meta.namespace);
            scriptObj.put("version", meta.version);
            scriptObj.put("description", meta.description);
            scriptObj.put("runAt", meta.runAt);
            gmInfo.put("script", scriptObj);
            gmInfo.put("scriptHandler", "Acid");
            gmInfo.put("version", BuildInfo.VERSION_NAME);
        } catch (Exception ignored) {}
        String gmInfoJson = gmInfo.toString();

        StringBuilder sb = new StringBuilder();
        sb.append("(function(){\n");
        sb.append("try {\n");
        // Re-entrance guard per page load
        sb.append("  window.__acidInjectedUserscripts = window.__acidInjectedUserscripts || {};\n");
        sb.append("  if (window.__acidInjectedUserscripts[").append(guardKey).append("]) return;\n");
        sb.append("  window.__acidInjectedUserscripts[").append(guardKey).append("] = true;\n\n");

        // Global GM menu commands registry
        sb.append("  window.__acid_gm_menus = window.__acid_gm_menus || {};\n");
        sb.append("  window.__acid_gm_menus[").append(safeScriptId).append("] = window.__acid_gm_menus[").append(safeScriptId).append("] || {};\n\n");

        // Construct unsafeWindow shim
        sb.append("  var unsafeWindow = (function() {\n");
        sb.append("    if (typeof window !== 'undefined') return window;\n");
        sb.append("    try {\n");
        sb.append("      var el = document.createElement('p');\n");
        sb.append("      el.setAttribute('onclick', 'return window;');\n");
        sb.append("      return el.onclick();\n");
        sb.append("    } catch(e) { return window; }\n");
        sb.append("  })();\n");
        sb.append("  try { window.wrappedJSObject = unsafeWindow; } catch(e){}\n\n");

        // Helper random generator for asynchronous request callbacks
        sb.append("  var _acidRand = function(len) {\n");
        sb.append("    var s = '', chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';\n");
        sb.append("    for (var i = 0; i < len; i++) s += chars.charAt(Math.floor(Math.random() * chars.length));\n");
        sb.append("    return s;\n");
        sb.append("  };\n\n");

        // Native host bridge reference
        sb.append("  var _host = window.").append(UserscriptNativeBridge.JS_INTERFACE_NAME).append(";\n");
        sb.append("  var _callHost = function(act, obj) {\n");
        sb.append("    if (!_host || !_host.call) return '';\n");
        sb.append("    try { return _host.call(act, JSON.stringify(obj || {})); } catch(e){ return ''; }\n");
        sb.append("  };\n\n");

        // Storage Shims
        sb.append("  var GM_setValue = function(key, val) {\n");
        sb.append("    _callHost('setValue', { scriptId: ").append(safeScriptId).append(", name: String(key), value: val });\n");
        sb.append("  };\n");

        sb.append("  var GM_getValue = function(key, defVal) {\n");
        sb.append("    var res = _callHost('getValue', { scriptId: ").append(safeScriptId).append(", name: String(key), defaultValue: defVal });\n");
        sb.append("    if (!res) return defVal;\n");
        sb.append("    try { var parsed = JSON.parse(res); return parsed.value !== undefined ? parsed.value : defVal; } catch(e){ return defVal; }\n");
        sb.append("  };\n");

        sb.append("  var GM_deleteValue = function(key) {\n");
        sb.append("    _callHost('deleteValue', { scriptId: ").append(safeScriptId).append(", name: String(key) });\n");
        sb.append("  };\n");

        sb.append("  var GM_listValues = function() {\n");
        sb.append("    var res = _callHost('listValues', { scriptId: ").append(safeScriptId).append(" });\n");
        sb.append("    try { return JSON.parse(res) || []; } catch(e){ return []; }\n");
        sb.append("  };\n\n");

        // DOM & Style Shims
        sb.append("  var GM_addStyle = function(css) {\n");
        sb.append("    if (!css) return null;\n");
        sb.append("    var h = document.head || document.getElementsByTagName('head')[0] || document.documentElement;\n");
        sb.append("    if (!h) return null;\n");
        sb.append("    var st = document.createElement('style');\n");
        sb.append("    st.type = 'text/css';\n");
        sb.append("    st.textContent = css;\n");
        sb.append("    h.appendChild(st);\n");
        sb.append("    return st;\n");
        sb.append("  };\n");

        sb.append("  var GM_addElement = function(parent, tag, attrs) {\n");
        sb.append("    if (typeof parent === 'string') { return GM_addElement(undefined, parent, tag || {}, attrs); }\n");
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

        // System & Menu Shims
        sb.append("  var GM_setClipboard = function(data, type) {\n");
        sb.append("    _callHost('setClipboard', { data: String(data || ''), type: type || 'text/plain' });\n");
        sb.append("  };\n");

        sb.append("  var GM_registerMenuCommand = function(name, fn) {\n");
        sb.append("    if (!name || typeof fn !== 'function') return name;\n");
        sb.append("    window.__acid_gm_menus[").append(safeScriptId).append("][name] = fn;\n");
        sb.append("    return name;\n");
        sb.append("  };\n");

        sb.append("  var GM_unregisterMenuCommand = function(name) {\n");
        sb.append("    if (!name) return;\n");
        sb.append("    delete window.__acid_gm_menus[").append(safeScriptId).append("][name];\n");
        sb.append("  };\n");

        sb.append("  var GM_log = function(msg) {\n");
        sb.append("    _callHost('log', { message: String(msg) });\n");
        sb.append("  };\n");

        sb.append("  var GM_info = ").append(gmInfoJson).append(";\n\n");

        // Network Shim (GM_xmlhttpRequest)
        sb.append("  var GM_xmlhttpRequest = function(details) {\n");
        sb.append("    if (!details || !details.url) return;\n");
        sb.append("    var id = '_acid_xhr_' + _acidRand(8);\n");
        sb.append("    if (details.onload) unsafeWindow[id + '_onload'] = details.onload;\n");
        sb.append("    if (details.onerror) unsafeWindow[id + '_onerror'] = details.onerror;\n");
        sb.append("    if (details.ontimeout) unsafeWindow[id + '_ontimeout'] = details.ontimeout;\n");
        sb.append("    var req = {\n");
        sb.append("      url: details.url,\n");
        sb.append("      method: (details.method || 'GET').toUpperCase(),\n");
        sb.append("      headers: details.headers || {},\n");
        sb.append("      data: details.data || null,\n");
        sb.append("      timeout: details.timeout || 15000,\n");
        sb.append("      responseType: details.responseType || 'text',\n");
        sb.append("      v_ua: navigator.userAgent,\n");
        sb.append("      onload: details.onload ? (id + '_onload') : '',\n");
        sb.append("      onerror: details.onerror ? (id + '_onerror') : '',\n");
        sb.append("      ontimeout: details.ontimeout ? (id + '_ontimeout') : ''\n");
        sb.append("    };\n");
        sb.append("    _callHost('xmlhttpRequest', req);\n");
        sb.append("  };\n");
        sb.append("  GM_xmlhttpRequest.UNSENT = 0;\n");
        sb.append("  GM_xmlhttpRequest.OPENED = 1;\n");
        sb.append("  GM_xmlhttpRequest.HEADERS_RECEIVED = 2;\n");
        sb.append("  GM_xmlhttpRequest.LOADING = 3;\n");
        sb.append("  GM_xmlhttpRequest.DONE = 4;\n\n");

        // Modern Promise-based GM.* Object
        sb.append("  var GM = {\n");
        sb.append("    info: GM_info,\n");
        sb.append("    setValue: function(k, v) { return Promise.resolve(GM_setValue(k, v)); },\n");
        sb.append("    getValue: function(k, d) { return Promise.resolve(GM_getValue(k, d)); },\n");
        sb.append("    deleteValue: function(k) { return Promise.resolve(GM_deleteValue(k)); },\n");
        sb.append("    listValues: function() { return Promise.resolve(GM_listValues()); },\n");
        sb.append("    addStyle: function(css) { return Promise.resolve(GM_addStyle(css)); },\n");
        sb.append("    addElement: function(p, t, a) { return Promise.resolve(GM_addElement(p, t, a)); },\n");
        sb.append("    setClipboard: function(d, t) { return Promise.resolve(GM_setClipboard(d, t)); },\n");
        sb.append("    registerMenuCommand: function(n, f) { return Promise.resolve(GM_registerMenuCommand(n, f)); },\n");
        sb.append("    xmlHttpRequest: function(d) {\n");
        sb.append("      return new Promise(function(resolve, reject) {\n");
        sb.append("        var copy = Object.assign({}, d);\n");
        sb.append("        copy.onload = function(r) { resolve(r); if (d.onload) d.onload(r); };\n");
        sb.append("        copy.onerror = function(e) { reject(e); if (d.onerror) d.onerror(e); };\n");
        sb.append("        GM_xmlhttpRequest(copy);\n");
        sb.append("      });\n");
        sb.append("    }\n");
        sb.append("  };\n\n");

        // Soft Sandbox Closure
        sb.append("  (function(window, unsafeWindow, GM, GM_setValue, GM_getValue, GM_deleteValue, GM_listValues, GM_addStyle, GM_addElement, GM_setClipboard, GM_registerMenuCommand, GM_unregisterMenuCommand, GM_xmlhttpRequest, GM_log, GM_info) {\n");

        // Bundled @require dependencies
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

        // Script Body
        sb.append("\n    /* === Script Body === */\n");
        sb.append("    ").append(meta.body).append("\n");

        sb.append("  })(unsafeWindow, unsafeWindow, GM, GM_setValue, GM_getValue, GM_deleteValue, GM_listValues, GM_addStyle, GM_addElement, GM_setClipboard, GM_registerMenuCommand, GM_unregisterMenuCommand, GM_xmlhttpRequest, GM_log, GM_info);\n");

        sb.append("} catch(e) {\n");
        sb.append("  console.error('[AcidUserscriptError in ").append(scriptId).append("]', e);\n");
        sb.append("}\n");
        sb.append("})();");

        return sb.toString();
    }
}
