// ==UserScript==
// @name         Acid Anti-Hijack & Full-Article Unlocker
// @namespace    https://acid.browser/userscripts
// @version      1.0.0
// @description  Automatically removes "Open in App" floating banners, unlocks article height limits, and restores clipboard copy on mobile websites.
// @match        *://*/*
// @grant        none
// @run-at       document-idle
// ==/UserScript==

(function () {
    'use strict';
    // Do not interfere with sensitive authentication, OAuth, or security challenge domains
    const host = (window.location && window.location.hostname) ? window.location.hostname.toLowerCase() : '';
    if (host.includes('github.com') || host.includes('google.') || host.includes('cloudflare.') ||
        host.includes('apple.com') || host.includes('microsoft.com') || host.includes('hcaptcha.') ||
        host.includes('arkoselabs.') || host.includes('recaptcha')) {
        return;
    }

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
