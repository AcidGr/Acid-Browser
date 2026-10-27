// ==UserScript==
// @name         Acid Anti-Hijack & Full-Article Unlocker
// @namespace    https://acid.browser/userscripts
// @version      1.0.0
// @description  Automatically removes "Open in App" floating banners, unlocks article height limits, and restores clipboard copy on mobile websites.
// @match        *://*/*
// @run-at       document-idle
// ==/UserScript==

(function () {
    'use strict';
    if (window.__acidAntiHijackLoaded) return;
    window.__acidAntiHijackLoaded = true;

    // Allow text selection and copy
    document.addEventListener('copy', function (e) {
        e.stopImmediatePropagation();
    }, true);

    const style = document.createElement('style');
    style.textContent = `
        * { -webkit-user-select: text !important; user-select: text !important; }
        .open-in-app, .OpenInAppButton, .MobileAppHeader-downloadLink,
        [class*="openApp"], [class*="OpenApp"], [id*="open-app"] {
            display: none !important;
        }
    `;
    if (document.head) {
        document.head.appendChild(style);
    }
})();
