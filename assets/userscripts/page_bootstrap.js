(function(){
  if (window.__acid_bootstrapped) return;
  window.__acid_bootstrapped = true;
  var fired = {};
  function req(phase) {
    if (fired[phase]) return; fired[phase] = 1;
    var host = window.__acid_gm_host__;
    if (!host || !host.call) return;
    try { host.call('requestInjection', JSON.stringify({ runAt: phase })); } catch(e){}
  }
  function headReady() {
    if (document.head) { req(1); return true; }
    return false;
  }
  try {
    if (!headReady()) {
      var mo = new MutationObserver(function(){ if (headReady()) mo.disconnect(); });
      mo.observe(document.documentElement || document, { childList: true, subtree: true });
    }
  } catch(e) {}
  try {
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', function(){ req(2); });
    } else { req(2); }
  } catch(e) {}
  try {
    if (document.readyState === 'complete') { req(4); }
    else { window.addEventListener('load', function(){ req(4); }); }
  } catch(e) {}
})();
