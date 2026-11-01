/**
 * Userscript HTTP response unpacker & callback dispatcher.
 * Unpacks json, document, blob, arraybuffer and stream response types,
 * then dispatches to the registered window[cbName] handler.
 */
(function() {
  window.__acid_dispatch_http_cb = window.__acid_dispatch_http_cb || function(name, obj, last) {
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
})();
