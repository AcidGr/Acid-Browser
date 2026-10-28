/**
 * Acid-broswer In-Page Agent Bridge Runtime (v0.1.0)
 * Combines DOM semantic distillation, Set-of-Marks (SoM) overlay,
 * anti-occlusion top-element verification, React/Vue-safe form filling,
 * and Desktop/Mobile viewport spoofing.
 */
(function () {
    if (window.__acidBridge && window.__acidBridge.__initialized) {
        return;
    }

    // 1. Patch attachShadow so closed Shadow DOM roots remain inspectable by the agent
    try {
        const origAttachShadow = Element.prototype.attachShadow;
        if (origAttachShadow && !Element.prototype.__acidPatched) {
            Element.prototype.attachShadow = function (init) {
                const root = origAttachShadow.call(this, init);
                try {
                    Object.defineProperty(this, '__acidShadowRoot', {
                        value: root,
                        configurable: true,
                        enumerable: false
                    });
                } catch (e) {}
                return root;
            };
            Element.prototype.__acidPatched = true;
        }
    } catch (e) {}

    // 2. Hide webdriver automation flag
    try {
        Object.defineProperty(navigator, 'webdriver', {
            get: () => false,
            configurable: true
        });
    } catch (e) {}

    const SOM_OVERLAY_ID = '__acid_som_overlay__';
    let refMap = {};
    let refMetaMap = {};
    let documentRevision = 1;
    let lastMutationTime = Date.now();
    let somEnabled = false;
    let desktopSpoofEnabled = false;
    let viewportObserver = null;

    // 3. Track DOM mutations for postcondition verification and DOM quiet checks
    try {
        const mutObserver = new MutationObserver(function (mutations) {
            for (let i = 0; i < mutations.length; i++) {
                const target = mutations[i].target;
                if (target && (target.id === SOM_OVERLAY_ID || (target.closest && target.closest('#' + SOM_OVERLAY_ID)))) {
                    continue;
                }
                documentRevision++;
                lastMutationTime = Date.now();
                break;
            }
        });
        if (document.documentElement) {
            mutObserver.observe(document.documentElement, {
                childList: true,
                subtree: true,
                attributes: true,
                characterData: true
            });
        }
    } catch (e) {}

    // 4. Desktop Mode Viewport & Hardware Fingerprint Spoofer
    function enforceDesktopViewport(enable) {
        desktopSpoofEnabled = !!enable;
        if (!desktopSpoofEnabled) {
            if (viewportObserver) {
                viewportObserver.disconnect();
                viewportObserver = null;
            }
            return { ok: true, mode: 'mobile' };
        }

        try {
            Object.defineProperty(navigator, 'maxTouchPoints', { get: () => 0, configurable: true });
            Object.defineProperty(navigator, 'platform', { get: () => 'Win32', configurable: true });
            Object.defineProperty(navigator, 'vendor', { get: () => 'Google Inc.', configurable: true });
            if (window.screen) {
                Object.defineProperty(window.screen, 'width', { get: () => 1920, configurable: true });
                Object.defineProperty(window.screen, 'height', { get: () => 1080, configurable: true });
                Object.defineProperty(window.screen, 'availWidth', { get: () => 1920, configurable: true });
                Object.defineProperty(window.screen, 'availHeight', { get: () => 1040, configurable: true });
            }
        } catch (e) {}

        function lockViewportMeta() {
            if (!document.head) return;
            const targetContent = 'width=1280, initial-scale=0.32, minimum-scale=0.1, maximum-scale=5.0, user-scalable=yes';
            let meta = document.head.querySelector('meta[name="viewport"]');
            if (!meta) {
                meta = document.createElement('meta');
                meta.name = 'viewport';
                meta.content = targetContent;
                document.head.insertBefore(meta, document.head.firstChild);
            } else if (meta.content !== targetContent) {
                meta.content = targetContent;
            }
        }

        lockViewportMeta();
        if (!viewportObserver && document.head) {
            viewportObserver = new MutationObserver(() => {
                if (desktopSpoofEnabled) lockViewportMeta();
            });
            viewportObserver.observe(document.head, { childList: true, subtree: false });
        }
        return { ok: true, mode: 'desktop' };
    }

    // 5. Element Visibility & Top-Layer Anti-Occlusion Filter
    const INTERACTIVE_TAGS = new Set([
        'a', 'button', 'input', 'select', 'textarea', 'summary', 'details', 'label'
    ]);
    const INTERACTIVE_ROLES = new Set([
        'button', 'link', 'textbox', 'searchbox', 'combobox', 'listbox',
        'option', 'checkbox', 'radio', 'switch', 'tab', 'menuitem',
        'menuitemcheckbox', 'menuitemradio', 'slider', 'spinbutton'
    ]);
    const IGNORED_TAGS = new Set([
        'script', 'style', 'noscript', 'meta', 'link', 'head', 'svg', 'path', 'defs', 'symbol'
    ]);

    function isElementVisible(el, rect, style) {
        if (!style) return false;
        if (style.display === 'none' || style.visibility === 'hidden' || style.visibility === 'collapse') {
            return false;
        }
        if (parseFloat(style.opacity) <= 0.02) {
            return false;
        }
        if (rect.width < 4 || rect.height < 4) {
            return false;
        }
        return true;
    }

    function isInViewport(rect, marginPx) {
        const vw = window.innerWidth || document.documentElement.clientWidth || 360;
        const vh = window.innerHeight || document.documentElement.clientHeight || 640;
        return (
            rect.bottom >= -marginPx &&
            rect.top <= vh + marginPx &&
            rect.right >= -marginPx &&
            rect.left <= vw + marginPx
        );
    }

    function isTopMostAtCenter(el, rect) {
        const vw = window.innerWidth || document.documentElement.clientWidth;
        const vh = window.innerHeight || document.documentElement.clientHeight;
        const cx = Math.min(Math.max(rect.left + rect.width / 2, 1), vw - 1);
        const cy = Math.min(Math.max(rect.top + rect.height / 2, 1), vh - 1);

        if (cx < 0 || cy < 0 || cx > vw || cy > vh) {
            return true; // Off-screen elements skip occlusion point check
        }

        let root = el.getRootNode ? el.getRootNode() : document;
        if (!root || typeof root.elementFromPoint !== 'function') {
            root = document;
        }
        const hit = root.elementFromPoint(cx, cy);
        if (!hit) return true;
        if (hit === el || el.contains(hit) || hit.contains(el)) {
            return true;
        }
        // Check label association
        if (hit.tagName && hit.tagName.toLowerCase() === 'label' && hit.control === el) {
            return true;
        }
        return false;
    }

    function isElementInteractive(el, style) {
        const tag = (el.tagName || '').toLowerCase();
        if (!tag || IGNORED_TAGS.has(tag)) return false;

        if (tag === 'input' && (el.type || '').toLowerCase() === 'hidden') {
            return false;
        }
        if (tag === 'label') {
            // Skip standalone <label> if its associated form control is already visible in DOM
            if (el.control) return false;
            return false;
        }
        if (INTERACTIVE_TAGS.has(tag)) {
            return true;
        }

        const role = (el.getAttribute('role') || '').toLowerCase().trim();
        if (role && INTERACTIVE_ROLES.has(role)) {
            return true;
        }

        if (el.isContentEditable || el.getAttribute('contenteditable') === 'true') {
            return true;
        }

        if (
            el.hasAttribute('onclick') ||
            el.hasAttribute('ng-click') ||
            el.hasAttribute('@click') ||
            el.hasAttribute('v-on:click') ||
            el.hasAttribute('data-action')
        ) {
            return true;
        }

        const tabIdx = el.getAttribute('tabindex');
        if (tabIdx !== null && tabIdx !== '-1' && tag !== 'body' && tag !== 'main') {
            return true;
        }

        if (style && style.cursor === 'pointer') {
            const textLen = (el.innerText || '').trim().length;
            if (textLen > 0 && textLen <= 120 && el.children.length <= 6) {
                return true;
            }
        }
        return false;
    }

    function isSensitiveInput(el) {
        const tag = (el.tagName || '').toLowerCase();
        if (tag !== 'input') return false;
        const type = (el.type || '').toLowerCase();
        const auto = (el.getAttribute('autocomplete') || '').toLowerCase();
        const name = (el.name || el.id || '').toLowerCase();
        if (type === 'password') return true;
        if (auto.includes('password') || auto.includes('cc-') || auto.includes('one-time-code')) return true;
        if (name.includes('password') || name.includes('passwd') || name.includes('cvv')) return true;
        return false;
    }

    function cleanText(str, maxLen) {
        if (!str) return '';
        const cleaned = String(str).replace(/\s+/g, ' ').trim();
        if (maxLen && cleaned.length > maxLen) {
            return cleaned.slice(0, maxLen) + '…';
        }
        return cleaned;
    }

    function extractElementLabel(el) {
        let labelText = '';
        if (el.labels && el.labels.length > 0) {
            labelText = cleanText(el.labels[0].innerText || el.labels[0].textContent, 60);
        } else if (el.id) {
            try {
                const lbl = document.querySelector('label[for="' + el.id.replace(/"/g, '\\"') + '"]');
                if (lbl) labelText = cleanText(lbl.innerText || lbl.textContent, 60);
            } catch (e) {}
        }
        const aria = el.getAttribute('aria-label') || el.getAttribute('title') || el.getAttribute('placeholder') || el.getAttribute('alt');
        const inner = (el.tagName && el.tagName.toLowerCase() === 'select')
            ? ''
            : cleanText(el.innerText || el.textContent, 80);
        const primary = inner || labelText || aria || el.name || el.id || '';
        if (primary && aria && primary !== aria && !primary.includes(aria)) {
            return cleanText(primary + ' (' + aria + ')', 100);
        }
        return cleanText(primary, 80);
    }

    // 6. Set-of-Marks (SoM) Overlay Renderer
    function clearSomOverlay() {
        const existing = document.getElementById(SOM_OVERLAY_ID);
        if (existing && existing.parentNode) {
            existing.parentNode.removeChild(existing);
        }
    }

    function renderSomOverlay(items) {
        clearSomOverlay();
        if (!document.body || !items || items.length === 0) return;

        const container = document.createElement('div');
        container.id = SOM_OVERLAY_ID;
        container.style.cssText = 'position:fixed;top:0;left:0;width:100%;height:100%;pointer-events:none;z-index:2147483647;overflow:hidden;';

        const palette = ['#00FF88', '#00E5FF', '#FFB300', '#FF4081', '#B388FF'];

        for (let i = 0; i < items.length; i++) {
            const item = items[i];
            if (!item.inViewport) continue;
            const b = item.box;
            if (b.width <= 0 || b.height <= 0) continue;

            const color = palette[i % palette.length];
            const box = document.createElement('div');
            box.style.cssText =
                'position:fixed;' +
                'left:' + Math.round(b.x) + 'px;' +
                'top:' + Math.round(b.y) + 'px;' +
                'width:' + Math.round(b.width) + 'px;' +
                'height:' + Math.round(b.height) + 'px;' +
                'border:1.5px solid ' + color + ';' +
                'background:' + color + '14;' +
                'border-radius:3px;' +
                'box-sizing:border-box;' +
                'pointer-events:none;';

            const badge = document.createElement('span');
            badge.textContent = item.ref;
            badge.style.cssText =
                'position:absolute;' +
                'left:-1px;top:-15px;' +
                'background:' + color + ';' +
                'color:#0A0E14;' +
                'font:bold 10px/13px monospace;' +
                'padding:0 3px;' +
                'border-radius:2px;' +
                'white-space:nowrap;' +
                'pointer-events:none;';

            box.appendChild(badge);
            container.appendChild(box);
        }
        document.body.appendChild(container);
    }

    // 7. Main Observation Function
    function observe(options) {
        options = options || {};
        const viewportOnly = options.viewportOnly !== false;
        const marginPx = typeof options.marginPx === 'number' ? options.marginPx : 240;
        const maxElements = options.maxElements || 180;
        const showSom = options.includeSom !== undefined ? !!options.includeSom : somEnabled;

        refMap = {};
        refMetaMap = {};
        const items = [];
        let counter = 0;

        function walk(node) {
            if (!node || counter >= maxElements) return;
            if (node.nodeType !== Node.ELEMENT_NODE) return;
            if (node.id === SOM_OVERLAY_ID) return;

            const el = node;
            const tag = (el.tagName || '').toLowerCase();
            if (IGNORED_TAGS.has(tag)) return;

            const style = window.getComputedStyle(el);
            const rect = el.getBoundingClientRect();

            if (isElementVisible(el, rect, style)) {
                const inVp = isInViewport(rect, 0);
                const inExtendedVp = isInViewport(rect, marginPx);

                if ((!viewportOnly || inExtendedVp) && isElementInteractive(el, style)) {
                    if (!inVp || isTopMostAtCenter(el, rect)) {
                        counter++;
                        const ref = 'e' + counter;
                        refMap[ref] = el;

                        const role = (el.getAttribute('role') || '').toLowerCase() || tag;
                        const label = extractElementLabel(el);
                        const inputType = tag === 'input' ? (el.type || 'text').toLowerCase() : undefined;
                        const disabled = !!el.disabled || el.getAttribute('aria-disabled') === 'true';
                        const checked = (tag === 'input' && (inputType === 'checkbox' || inputType === 'radio')) ? !!el.checked : undefined;

                        let val = undefined;
                        if (tag === 'input' || tag === 'textarea' || tag === 'select') {
                            val = isSensitiveInput(el) ? '***' : cleanText(el.value, 60);
                        }

                        let href = undefined;
                        if (tag === 'a' && el.href) {
                            href = cleanText(el.getAttribute('href') || el.href, 100);
                        }

                        let optionsList = undefined;
                        if (tag === 'select' && el.options) {
                            optionsList = [];
                            for (let k = 0; k < Math.min(el.options.length, 15); k++) {
                                const opt = el.options[k];
                                optionsList.push(cleanText(opt.value + ':' + opt.text, 40));
                            }
                        }

                        const meta = {
                            ref: ref,
                            tag: tag,
                            role: role,
                            type: inputType,
                            label: label,
                            value: val,
                            href: href,
                            options: optionsList,
                            disabled: disabled,
                            checked: checked,
                            inViewport: inVp,
                            box: {
                                x: Math.round(rect.left),
                                y: Math.round(rect.top),
                                width: Math.round(rect.width),
                                height: Math.round(rect.height)
                            }
                        };
                        refMetaMap[ref] = meta;
                        items.push(meta);
                    }
                }
            }

            // Traverse Shadow DOM if present
            const shadow = el.shadowRoot || el.__acidShadowRoot;
            if (shadow && shadow.children) {
                for (let s = 0; s < shadow.children.length; s++) {
                    walk(shadow.children[s]);
                }
            }

            // Traverse same-origin iframe if accessible
            if (tag === 'iframe') {
                try {
                    const doc = el.contentDocument;
                    if (doc && doc.body) {
                        walk(doc.body);
                    }
                } catch (e) {}
            }

            const children = el.children;
            if (children) {
                for (let i = 0; i < children.length; i++) {
                    walk(children[i]);
                }
            }
        }

        if (document.body) {
            walk(document.body);
        }

        // Clear overlay before extracting body text so SoM badge numbers are not included in page excerpt
        clearSomOverlay();
        const bodyText = cleanText(document.body ? document.body.innerText : '', 1200);

        if (showSom) {
            renderSomOverlay(items);
        }

        // Build LLM-friendly compact outline (Playwright MCP + browser-use hybrid format)
        const scrollY = Math.round(window.scrollY || document.documentElement.scrollTop || 0);
        const vh = Math.round(window.innerHeight || document.documentElement.clientHeight || 0);
        const vw = Math.round(window.innerWidth || document.documentElement.clientWidth || 0);
        const scrollHeight = Math.round(Math.max(
            document.body ? document.body.scrollHeight : 0,
            document.documentElement ? document.documentElement.scrollHeight : 0,
            vh
        ));
        const maxScroll = Math.max(scrollHeight - vh, 1);
        const scrollPct = Math.min(100, Math.round((scrollY / maxScroll) * 100));

        const lines = [];
        lines.push('Page: ' + cleanText(document.title, 100));
        lines.push('URL: ' + location.href);
        lines.push('Mode: ' + (desktopSpoofEnabled ? 'Desktop(1280px)' : 'Mobile') +
            ' | Viewport: ' + vw + 'x' + vh +
            ' | Scroll: ' + scrollY + '/' + scrollHeight + ' (' + scrollPct + '%)' +
            ' | Rev: ' + documentRevision);
        lines.push('Interactive Elements (' + items.length + '):');

        for (let i = 0; i < items.length; i++) {
            const it = items[i];
            let desc = '[' + it.ref + '] ' + it.tag;
            if (it.type) desc += '(' + it.type + ')';
            else if (it.role && it.role !== it.tag) desc += '(' + it.role + ')';
            if (it.label) desc += ' "' + it.label + '"';
            if (it.value !== undefined && it.value !== '') desc += ' value="' + it.value + '"';
            if (it.checked !== undefined) desc += ' checked=' + it.checked;
            if (it.disabled) desc += ' [disabled]';
            if (it.href) desc += ' -> ' + it.href;
            if (it.options && it.options.length > 0) desc += ' options=[' + it.options.join(', ') + ']';
            desc += ' @(' + it.box.x + ',' + it.box.y + ',' + it.box.width + 'x' + it.box.height + ')';
            lines.push('  ' + desc);
        }

        if (bodyText) {
            lines.push('Page Text Excerpt:');
            lines.push('  ' + bodyText);
        }

        return {
            ok: true,
            url: location.href,
            title: document.title || '',
            mode: desktopSpoofEnabled ? 'desktop' : 'mobile',
            revision: documentRevision,
            msSinceLastMutation: Date.now() - lastMutationTime,
            viewport: {
                width: vw,
                height: vh,
                dpr: window.devicePixelRatio || 1,
                scrollY: scrollY,
                scrollHeight: scrollHeight,
                scrollPercent: scrollPct
            },
            elementCount: items.length,
            elements: items,
            compactText: lines.join('\n')
        };
    }

    // 8. Prepare Element for Native Touch / Click
    function prepareClick(ref) {
        const el = refMap[ref];
        if (!el) {
            return { ok: false, error: 'REF_NOT_FOUND', message: 'Element ref ' + ref + ' not found. Run observe first.' };
        }
        if (!el.isConnected) {
            return { ok: false, error: 'STALE_ELEMENT_REF', message: 'Element ' + ref + ' detached from DOM.' };
        }

        const vh = window.innerHeight || document.documentElement.clientHeight || 640;
        let rect = el.getBoundingClientRect();
        if (rect.top < vh * 0.1 || rect.bottom > vh * 0.9) {
            try {
                el.scrollIntoView({ block: 'center', inline: 'center', behavior: 'instant' });
            } catch (e) {
                el.scrollIntoView(true);
            }
            rect = el.getBoundingClientRect();
        }

        const cssX = rect.left + rect.width / 2;
        const cssY = rect.top + rect.height / 2;
        const vv = window.visualViewport;
        const scale = vv ? vv.scale : 1;
        const offsetLeft = vv ? vv.offsetLeft : 0;
        const offsetTop = vv ? vv.offsetTop : 0;

        return {
            ok: true,
            ref: ref,
            label: extractElementLabel(el),
            tag: (el.tagName || '').toLowerCase(),
            cssX: cssX,
            cssY: cssY,
            vvScale: scale,
            vvOffsetLeft: offsetLeft,
            vvOffsetTop: offsetTop,
            dpr: window.devicePixelRatio || 1,
            revisionBefore: documentRevision
        };
    }

    // 9. JS Synthetic Click Fallback
    function jsClick(ref) {
        const el = refMap[ref];
        if (!el || !el.isConnected) {
            return { ok: false, error: 'STALE_OR_MISSING_REF', ref: ref };
        }
        const revBefore = documentRevision;
        try {
            el.scrollIntoView({ block: 'center', inline: 'center', behavior: 'instant' });
        } catch (e) {}

        const rect = el.getBoundingClientRect();
        const cx = rect.left + rect.width / 2;
        const cy = rect.top + rect.height / 2;

        const opts = { bubbles: true, cancelable: true, view: window, clientX: cx, clientY: cy };
        try { el.dispatchEvent(new PointerEvent('pointerdown', opts)); } catch (e) {}
        try { el.dispatchEvent(new MouseEvent('mousedown', opts)); } catch (e) {}
        try { el.focus(); } catch (e) {}
        try { el.dispatchEvent(new PointerEvent('pointerup', opts)); } catch (e) {}
        try { el.dispatchEvent(new MouseEvent('mouseup', opts)); } catch (e) {}
        try { el.click(); } catch (e) {
            el.dispatchEvent(new MouseEvent('click', opts));
        }

        if (somEnabled) {
            setTimeout(() => observe({ includeSom: true }), 120);
        }

        return {
            ok: true,
            ref: ref,
            strategy: 'DOM_SYNTHETIC',
            revisionBefore: revBefore,
            revisionAfter: documentRevision
        };
    }

    // 10. React / Vue Safe Form Input Setter (Zero Soft-Keyboard Popup)
    function setSingleFieldValue(el, text, clear) {
        const tag = (el.tagName || '').toLowerCase();
        const finalText = clear === false ? ((el.value || '') + text) : String(text);

        if (tag === 'input' || tag === 'textarea') {
            const proto = tag === 'textarea' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
            const descriptor = Object.getOwnPropertyDescriptor(proto, 'value');
            const previousValue = el.value;

            if (descriptor && descriptor.set) {
                descriptor.set.call(el, finalText);
            } else {
                el.value = finalText;
            }

            // Reset React 16-19 internal _valueTracker so controlled inputs detect the change
            if (el._valueTracker) {
                try {
                    el._valueTracker.setValue(previousValue);
                } catch (e) {}
            }

            el.dispatchEvent(new Event('input', { bubbles: true, cancelable: true }));
            el.dispatchEvent(new Event('change', { bubbles: true, cancelable: true }));
            return true;
        } else if (el.isContentEditable || el.getAttribute('contenteditable') === 'true') {
            el.focus();
            el.innerText = finalText;
            el.dispatchEvent(new Event('input', { bubbles: true }));
            return true;
        }
        return false;
    }

    function fill(ref, text, clear, submit) {
        const el = refMap[ref];
        if (!el || !el.isConnected) {
            return { ok: false, error: 'REF_NOT_FOUND_OR_STALE', message: 'Element ' + ref + ' not available.' };
        }
        const revBefore = documentRevision;
        try {
            el.scrollIntoView({ block: 'center', inline: 'center', behavior: 'instant' });
        } catch (e) {}

        const applied = setSingleFieldValue(el, text, clear !== false);
        if (!applied) {
            return { ok: false, error: 'NOT_EDITABLE', message: 'Element ' + ref + ' is not an input/textarea/contenteditable.' };
        }

        if (submit) {
            const keyOpts = { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true };
            el.dispatchEvent(new KeyboardEvent('keydown', keyOpts));
            el.dispatchEvent(new KeyboardEvent('keypress', keyOpts));
            el.dispatchEvent(new KeyboardEvent('keyup', keyOpts));
            if (el.form) {
                try {
                    if (typeof el.form.requestSubmit === 'function') {
                        el.form.requestSubmit();
                    } else {
                        el.form.submit();
                    }
                } catch (e) {}
            }
        }

        if (somEnabled) {
            setTimeout(() => observe({ includeSom: true }), 100);
        }

        return {
            ok: true,
            ref: ref,
            value: isSensitiveInput(el) ? '***' : el.value,
            submitted: !!submit,
            revisionBefore: revBefore,
            revisionAfter: documentRevision
        };
    }

    function fillForm(fields, submitLast) {
        if (!Array.isArray(fields)) {
            return { ok: false, error: 'INVALID_FIELDS' };
        }
        const results = [];
        for (let i = 0; i < fields.length; i++) {
            const f = fields[i];
            const isLast = (i === fields.length - 1) && !!submitLast;
            results.push(fill(f.ref, f.text || f.value || '', f.clear !== false, isLast || !!f.submit));
        }
        return { ok: true, results: results, revision: documentRevision };
    }

    // 11. Select Option in <select> Dropdown
    function selectOption(ref, valueOrLabel) {
        const el = refMap[ref];
        if (!el || (el.tagName || '').toLowerCase() !== 'select') {
            return { ok: false, error: 'NOT_SELECT_ELEMENT', ref: ref };
        }
        const target = String(valueOrLabel).trim();
        let matchedIdx = -1;
        for (let i = 0; i < el.options.length; i++) {
            const opt = el.options[i];
            if (opt.value === target || opt.text.trim() === target || String(i) === target) {
                matchedIdx = i;
                break;
            }
        }
        if (matchedIdx === -1) {
            return { ok: false, error: 'OPTION_NOT_FOUND', target: target };
        }
        el.selectedIndex = matchedIdx;
        el.dispatchEvent(new Event('input', { bubbles: true }));
        el.dispatchEvent(new Event('change', { bubbles: true }));
        return {
            ok: true,
            ref: ref,
            selectedValue: el.options[matchedIdx].value,
            selectedText: el.options[matchedIdx].text
        };
    }

    // 12. Page & Container Scroll
    function scroll(direction, amount, targetRef) {
        const dir = (direction || 'down').toLowerCase();
        const factor = typeof amount === 'number' ? amount : 0.75;
        const container = (targetRef && refMap[targetRef]) ? refMap[targetRef] : window;
        const vh = window.innerHeight || 640;
        const vw = window.innerWidth || 360;

        let dx = 0;
        let dy = 0;
        if (dir === 'down') dy = Math.round(vh * factor);
        else if (dir === 'up') dy = -Math.round(vh * factor);
        else if (dir === 'right') dx = Math.round(vw * factor);
        else if (dir === 'left') dx = -Math.round(vw * factor);
        else if (dir === 'top') {
            window.scrollTo({ top: 0, behavior: 'instant' });
            return { ok: true, scrollY: 0 };
        } else if (dir === 'bottom') {
            window.scrollTo({ top: document.documentElement.scrollHeight, behavior: 'instant' });
            return { ok: true, scrollY: window.scrollY };
        }

        if (container === window) {
            window.scrollBy({ left: dx, top: dy, behavior: 'instant' });
        } else {
            container.scrollLeft += dx;
            container.scrollTop += dy;
        }

        if (somEnabled) {
            setTimeout(() => observe({ includeSom: true }), 100);
        }

        return {
            ok: true,
            direction: dir,
            scrollY: Math.round(window.scrollY || document.documentElement.scrollTop || 0)
        };
    }

    function setSomHighlight(enable) {
        somEnabled = !!enable;
        if (somEnabled) {
            return observe({ includeSom: true });
        } else {
            clearSomOverlay();
            return { ok: true, somEnabled: false };
        }
    }

    function getState() {
        return {
            ok: true,
            url: location.href,
            title: document.title || '',
            revision: documentRevision,
            msSinceLastMutation: Date.now() - lastMutationTime,
            somEnabled: somEnabled,
            desktopSpoofEnabled: desktopSpoofEnabled
        };
    }

    function checkCondition(cond, target, quietMs) {
        cond = (cond || '').toLowerCase();
        const now = Date.now();
        if (cond === 'networkidle' || cond === 'load' || cond === 'complete') {
            const ready = document.readyState === 'complete';
            const quiet = (now - lastMutationTime) >= (quietMs || 250);
            return { ok: true, satisfied: ready && quiet, readyState: document.readyState };
        }
        if (cond === 'stable') {
            const quiet = (now - lastMutationTime) >= (quietMs || 300);
            return { ok: true, satisfied: quiet, msSinceMutation: now - lastMutationTime };
        }
        if (cond === 'selector') {
            if (!target) return { ok: false, error: 'Target selector required' };
            try {
                const el = document.querySelector(target);
                const satisfied = !!(el && (el.offsetWidth > 0 || el.offsetHeight > 0 || el.getClientRects().length > 0));
                return { ok: true, satisfied: satisfied };
            } catch (e) {
                return { ok: false, error: String(e) };
            }
        }
        if (cond === 'text') {
            if (!target) return { ok: false, error: 'Target text required' };
            const pageText = document.body ? (document.body.innerText || '') : '';
            return { ok: true, satisfied: pageText.includes(target) };
        }
        if (cond === 'gone') {
            if (!target) return { ok: false, error: 'Target required for gone condition' };
            let found = false;
            try {
                const el = document.querySelector(target);
                if (el && (el.offsetWidth > 0 || el.offsetHeight > 0)) {
                    found = true;
                }
            } catch (ignored) {}
            if (!found) {
                const pageText = document.body ? (document.body.innerText || '') : '';
                if (pageText.includes(target)) {
                    found = true;
                }
            }
            return { ok: true, satisfied: !found };
        }
        return { ok: false, error: 'Unknown condition: ' + cond };
    }

    window.__acidBridge = {
        __initialized: true,
        observe: observe,
        prepareClick: prepareClick,
        jsClick: jsClick,
        fill: fill,
        fillForm: fillForm,
        selectOption: selectOption,
        scroll: scroll,
        setSomHighlight: setSomHighlight,
        enforceDesktopViewport: enforceDesktopViewport,
        clearSomOverlay: clearSomOverlay,
        getState: getState,
        checkCondition: checkCondition
    };
})();
