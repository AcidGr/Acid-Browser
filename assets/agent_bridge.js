/**
 * Acid-browser In-Page Agent Bridge Runtime (v0.1.0)
 * Combines DOM semantic distillation, Set-of-Marks (SoM) overlay,
 * anti-occlusion top-element verification, React/Vue-safe form filling,
 * and Desktop/Mobile viewport spoofing.
 */
(function () {
    if (window.__acidBridge && window.__acidBridge.__initialized) {
        return;
    }

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
            let hasExternalChange = false;
            for (let i = 0; i < mutations.length; i++) {
                const m = mutations[i];
                const target = m.target;
                if (target) {
                    if (target.id === SOM_OVERLAY_ID || (target.closest && target.closest('#' + SOM_OVERLAY_ID))) continue;
                    if (target.getAttribute && target.getAttribute('data-acid-internal') === 'true') continue;
                }
                if (m.addedNodes && m.addedNodes.length > 0) {
                    let allInternal = true;
                    for (let j = 0; j < m.addedNodes.length; j++) {
                        const n = m.addedNodes[j];
                        if (!n.getAttribute || n.getAttribute('data-acid-internal') !== 'true') {
                            allInternal = false;
                            break;
                        }
                    }
                    if (allInternal) continue;
                }
                if (m.removedNodes && m.removedNodes.length > 0) {
                    let allRemovedInternal = true;
                    for (let j = 0; j < m.removedNodes.length; j++) {
                        const n = m.removedNodes[j];
                        if (n.id !== SOM_OVERLAY_ID && (!n.getAttribute || n.getAttribute('data-acid-internal') !== 'true')) {
                            allRemovedInternal = false;
                            break;
                        }
                    }
                    if (allRemovedInternal) continue;
                }
                hasExternalChange = true;
                break;
            }
            if (hasExternalChange) {
                documentRevision++;
                lastMutationTime = Date.now();
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
            if (window.__acid_desktop_observer) {
                try { window.__acid_desktop_observer.disconnect(); } catch (e) {}
                window.__acid_desktop_observer = null;
            }
            const desktopMetas = document.querySelectorAll('meta[data-acid-viewport="desktop"]');
            desktopMetas.forEach((m) => m.remove());
            if (!document.querySelector('meta[name="viewport"]')) {
                const head = document.head || document.getElementsByTagName('head')[0] || document.documentElement;
                if (head) {
                    const meta = document.createElement('meta');
                    meta.name = 'viewport';
                    meta.content = 'width=device-width, initial-scale=1.0, user-scalable=yes';
                    head.appendChild(meta);
                }
            }
            return { ok: true, mode: 'mobile' };
        }

        function lockViewportMeta() {
            const head = document.head || document.getElementsByTagName('head')[0] || document.documentElement;
            if (!head) return;
            const targetContent = 'width=1280, initial-scale=0.32, minimum-scale=0.1, maximum-scale=5.0, user-scalable=yes';
            const metas = document.querySelectorAll('meta[name="viewport"]');
            let alreadyLocked = false;
            metas.forEach((m) => {
                if (m.getAttribute('data-acid-viewport') === 'desktop' && m.content === targetContent) {
                    alreadyLocked = true;
                } else {
                    m.remove();
                }
            });
            if (!alreadyLocked) {
                const meta = document.createElement('meta');
                meta.name = 'viewport';
                meta.content = targetContent;
                meta.setAttribute('data-acid-viewport', 'desktop');
                head.insertBefore(meta, head.firstChild);
            }
        }

        lockViewportMeta();
        if (!window.__acid_desktop_observer) {
            const head = document.head || document.getElementsByTagName('head')[0] || document.documentElement;
            if (head) {
                viewportObserver = new MutationObserver(() => {
                    if (desktopSpoofEnabled) lockViewportMeta();
                });
                window.__acid_desktop_observer = viewportObserver;
                viewportObserver.observe(head, { childList: true, subtree: false });
            }
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
        'menuitemcheckbox', 'menuitemradio', 'slider', 'spinbutton',
        'treeitem'
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
            const rect = el.getBoundingClientRect();
            // Allow clickable cards, table rows, and list items (avoid huge page wrappers > 280px high)
            if (rect.height > 0 && rect.height <= 280 && rect.width > 0) {
                const textLen = (el.innerText || '').trim().length;
                if (textLen > 0 && textLen <= 350) {
                    return true;
                }
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
        // 1. Check aria-labelledby
        const labelledBy = el.getAttribute('aria-labelledby');
        if (labelledBy) {
            const parts = labelledBy.trim().split(/\s+/);
            const collected = [];
            for (let p = 0; p < parts.length; p++) {
                const target = document.getElementById(parts[p]);
                if (target) {
                    const t = cleanText(target.innerText || target.textContent, 50);
                    if (t) collected.push(t);
                }
            }
            if (collected.length > 0) {
                labelText = cleanText(collected.join(' - '), 80);
            }
        }

        // 2. Check native <label>
        if (!labelText && el.labels && el.labels.length > 0) {
            labelText = cleanText(el.labels[0].innerText || el.labels[0].textContent, 60);
        } else if (!labelText && el.id) {
            try {
                const lbl = document.querySelector('label[for="' + el.id.replace(/"/g, '\\"') + '"]');
                if (lbl) labelText = cleanText(lbl.innerText || lbl.textContent, 60);
            } catch (e) {}
        }

        // 3. Check aria-label / title / placeholder / alt
        const aria = el.getAttribute('aria-label') || el.getAttribute('title') || el.getAttribute('placeholder') || el.getAttribute('alt');

        // 4. Check inner text or child SVG title
        let inner = (el.tagName && el.tagName.toLowerCase() === 'select')
            ? ''
            : cleanText(el.innerText || el.textContent, 90);

        if (!inner && !aria && !labelText) {
            const svgTitle = el.querySelector && el.querySelector('svg title, svg[aria-label]');
            if (svgTitle) {
                inner = cleanText(svgTitle.textContent || svgTitle.getAttribute('aria-label'), 60);
            }
        }

        const primary = inner || labelText || aria || el.name || el.id || '';
        if (primary && aria && primary !== aria && !primary.includes(aria)) {
            return cleanText(primary + ' (' + aria + ')', 100);
        }
        return cleanText(primary, 90);
    }

    function extractBadge(el) {
        try {
            const badgeEl = el.querySelector && el.querySelector('.badge, .count, .unread, [class*="badge"], [class*="count"], [aria-label*="未读"], [aria-label*="条"]');
            if (badgeEl) {
                const t = cleanText(badgeEl.innerText || badgeEl.textContent, 15);
                if (t && t.length <= 10 && t !== extractElementLabel(el)) return t;
            }
        } catch (e) {}
        return undefined;
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
        container.setAttribute('data-acid-internal', 'true');
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
        const offset = typeof options.offset === 'number' && options.offset >= 0 ? Math.floor(options.offset) : 0;
        const limit = typeof options.limit === 'number' && options.limit > 0 ? Math.floor(options.limit) :
                      (typeof options.maxElements === 'number' ? Math.floor(options.maxElements) : 180);
        // Default viewportOnly: false for observation snapshots so total candidates and pagination
        // stay 100% deterministic and constant regardless of offset or scroll position.
        const viewportOnly = options.viewportOnly !== undefined
            ? !!options.viewportOnly
            : false;
        const marginPx = typeof options.marginPx === 'number' ? options.marginPx : 240;
        const showSom = options.includeSom !== undefined ? !!options.includeSom : somEnabled;

        refMap = {};
        refMetaMap = {};
        const items = [];
        let counter = 0;
        let totalCandidates = 0;

        function isRedundantChildOf(el, label, rect, parent) {
            if (!parent || !label) return false;
            const tag = (el.tagName || '').toLowerCase();
            const role = (el.getAttribute('role') || '').toLowerCase().trim();

            // Distinct action widgets are never redundant text children
            if (tag === 'input' || tag === 'select' || tag === 'textarea' || tag === 'button') return false;
            if (tag === 'a' && el.hasAttribute('href')) return false;
            if (role && (role === 'button' || role === 'link' || role === 'checkbox' ||
                         role === 'radio' || role === 'switch' || role === 'tab' ||
                         role === 'menuitem' || role === 'option')) {
                return false;
            }
            if (el.hasAttribute('onclick') || el.hasAttribute('ng-click') ||
                el.hasAttribute('@click') || el.hasAttribute('v-on:click') ||
                el.hasAttribute('data-action')) {
                return false;
            }

            // Check if child text is contained inside parent container's text and child rect is within parent rect
            const pb = parent.box;
            if (parent.label && (parent.label === label || parent.label.includes(label))) {
                if (rect.left >= pb.x - 4 && rect.top >= pb.y - 4 &&
                    rect.right <= pb.x + pb.width + 4 && rect.bottom <= pb.y + pb.height + 4) {
                    return true;
                }
            }
            return false;
        }

        // Geometric sibling duplicate collision check (e.g. Gmail sidebar span right over a)
        const recentPlaced = [];
        function isGeometricSiblingDuplicate(el, rect) {
            const tag = (el.tagName || '').toLowerCase();
            const role = (el.getAttribute('role') || '').toLowerCase().trim();
            for (let k = recentPlaced.length - 1; k >= 0; k--) {
                const prev = recentPlaced[k];
                const pb = prev.box;
                const samePos = Math.abs(rect.left - pb.x) <= 4 &&
                                Math.abs(rect.top - pb.y) <= 4 &&
                                Math.abs(rect.width - pb.width) <= 6 &&
                                Math.abs(rect.height - pb.height) <= 6;
                if (samePos) {
                    const isCurAction = tag === 'a' || tag === 'button' || tag === 'input' || role === 'button' || role === 'link';
                    const isPrevAction = prev.tag === 'a' || prev.tag === 'button' || prev.tag === 'input' || prev.role === 'button' || prev.role === 'link';
                    if (isPrevAction && !isCurAction) {
                        return true;
                    }
                }
            }
            return false;
        }

        function walk(node, activeParent) {
            if (!node) return;
            if (node.nodeType !== Node.ELEMENT_NODE) return;
            if (node.id === SOM_OVERLAY_ID) return;

            const el = node;
            const tag = (el.tagName || '').toLowerCase();
            if (IGNORED_TAGS.has(tag)) return;

            const style = window.getComputedStyle(el);
            const rect = el.getBoundingClientRect();
            let nextParent = activeParent;

            if (isElementVisible(el, rect, style)) {
                const inVp = isInViewport(rect, 0);
                const inExtendedVp = isInViewport(rect, marginPx);

                if ((!viewportOnly || inExtendedVp) && isElementInteractive(el, style)) {
                    if (!inVp || isTopMostAtCenter(el, rect)) {
                        const role = (el.getAttribute('role') || '').toLowerCase().trim() || tag;
                        const label = extractElementLabel(el);

                        const isRedundant = isRedundantChildOf(el, label, rect, activeParent) ||
                                            isGeometricSiblingDuplicate(el, rect);

                        if (!isRedundant && (label || tag !== 'div' && tag !== 'span' || role !== tag)) {
                            totalCandidates++;

                            const currentCandidate = {
                                tag: tag,
                                role: role,
                                label: cleanText(label, 80),
                                box: {
                                    x: Math.round(rect.left),
                                    y: Math.round(rect.top),
                                    width: Math.round(rect.width),
                                    height: Math.round(rect.height)
                                }
                            };
                            recentPlaced.push(currentCandidate);
                            if (recentPlaced.length > 8) recentPlaced.shift();
                            nextParent = currentCandidate;

                            // Only record item if within the requested [offset, offset + limit) slice
                            if (totalCandidates > offset && items.length < limit) {
                                counter++;
                                const ref = 'e' + counter;
                                refMap[ref] = el;
                                refMetaMap[ref] = {
                                    tag: tag,
                                    role: role,
                                    label: cleanText(label, 80),
                                    id: el.id || '',
                                    className: (el.className || '').toString().trim(),
                                    name: el.getAttribute('name') || '',
                                    href: el.getAttribute('href') || ''
                                };
                                const inputType = tag === 'input' ? (el.type || 'text').toLowerCase() : undefined;
                                const disabled = !!el.disabled || el.getAttribute('aria-disabled') === 'true';
                                const readonly = !!el.readOnly || el.getAttribute('aria-readonly') === 'true';
                                const checked = (tag === 'input' && (inputType === 'checkbox' || inputType === 'radio')) ? !!el.checked : undefined;
                                const ariaExp = el.getAttribute('aria-expanded');
                                const ariaSel = el.getAttribute('aria-selected');
                                const expanded = ariaExp !== null ? ariaExp === 'true' : undefined;
                                const selected = ariaSel !== null ? ariaSel === 'true' : undefined;
                                const badge = extractBadge(el);

                                let val = undefined;
                                if (tag === 'input' || tag === 'textarea' || tag === 'select') {
                                    val = isSensitiveInput(el) ? '***' : cleanText(el.value, 60);
                                } else if (el.isContentEditable || el.getAttribute('contenteditable') === 'true') {
                                    val = cleanText(el.innerText || el.textContent, 60);
                                } else if (el.hasAttribute('aria-valuenow')) {
                                    val = el.getAttribute('aria-valuenow');
                                }

                                let href = undefined;
                                if (tag === 'a' && el.href) {
                                    let rawHref = el.getAttribute('href') || el.href;
                                    if (rawHref && rawHref !== '#' && !rawHref.startsWith('javascript:')) {
                                        if (rawHref.includes('?') && rawHref.length > 45) {
                                            try {
                                                const u = new URL(rawHref, location.href);
                                                const q = u.searchParams.get('q') || u.searchParams.get('query') || u.searchParams.get('wd');
                                                rawHref = q ? (u.pathname + '?q=' + encodeURIComponent(q)) : u.pathname;
                                            } catch (e) {
                                                rawHref = rawHref.split('?')[0];
                                            }
                                        }
                                        href = cleanText(rawHref, 75);
                                    }
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
                                    badge: badge,
                                    href: href,
                                    options: optionsList,
                                    disabled: disabled,
                                    readonly: readonly ? true : undefined,
                                    checked: checked,
                                    expanded: expanded,
                                    selected: selected,
                                    inViewport: inVp,
                                    box: {
                                        x: Math.round(rect.left),
                                        y: Math.round(rect.top),
                                        width: Math.round(rect.width),
                                        height: Math.round(rect.height)
                                    }
                                };
                                refMetaMap[ref].inViewport = inVp;
                                refMetaMap[ref].box = meta.box;
                                items.push(meta);
                            }
                        }
                    }
                }
            }

            // Traverse Shadow DOM if present
            const shadow = el.shadowRoot || el.__acidShadowRoot;
            if (shadow && shadow.children) {
                for (let s = 0; s < shadow.children.length; s++) {
                    walk(shadow.children[s], nextParent);
                }
            }

            // Traverse same-origin iframe if accessible
            if (tag === 'iframe') {
                try {
                    const doc = el.contentDocument;
                    if (doc && doc.body) {
                        walk(doc.body, nextParent);
                    }
                } catch (e) {}
            }

            const children = el.children;
            if (children) {
                for (let i = 0; i < children.length; i++) {
                    walk(children[i], nextParent);
                }
            }
        }

        let rootNode = document.body;
        let scopedSelector = null;
        if (options.selector || options.scope) {
            try {
                const sel = (options.selector || options.scope).trim();
                const matched = document.querySelector(sel);
                if (matched) {
                    rootNode = matched;
                    scopedSelector = sel;
                }
            } catch (e) {}
        }

        if (rootNode) {
            walk(rootNode, null);
        }

        // Expose self-healing window.__refs for direct access via eval
        try {
            window.__refs = new Proxy(refMap, {
                get: function(target, prop) {
                    if (typeof prop !== 'string') return target[prop];
                    const el = target[prop];
                    if (el && el.isConnected) return el;
                    const res = resolveTargetElement({ ref: prop });
                    return res.ok ? res.el : undefined;
                }
            });
            window.__refMeta = refMetaMap;
        } catch (e) {}

        // Clear overlay before extracting body text so SoM badge numbers are not included in page excerpt
        clearSomOverlay();
        const fullBodyText = document.body ? (document.body.innerText || '') : '';
        const totalTextChars = fullBodyText.length;
        const textOffset = typeof options.textOffset === 'number' && options.textOffset >= 0 ? Math.floor(options.textOffset) : 0;
        const textLimit = typeof options.textLimit === 'number' && options.textLimit > 0 ? Math.floor(options.textLimit) : 1200;
        const slicedText = cleanText(fullBodyText.slice(textOffset, textOffset + textLimit), textLimit);

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
        if (scopedSelector) {
            lines.push('Scope: ' + scopedSelector);
        }
        lines.push('Mode: ' + (desktopSpoofEnabled ? 'Desktop(1280px)' : 'Mobile') +
            ' | Viewport: ' + vw + 'x' + vh +
            ' | Scroll: ' + scrollY + '/' + scrollHeight + ' (' + scrollPct + '%)' +
            ' | Rev: ' + documentRevision);

        const endIdx = offset + items.length;
        let elementsHeader = 'Interactive Elements (' + items.length;
        if (items.length === 0) {
            if (offset > 0 || totalCandidates > 0) {
                elementsHeader += ' | offset=' + offset + ', total=' + totalCandidates;
            }
        } else if (totalCandidates > items.length || offset > 0) {
            elementsHeader += ' | showing ' + (offset + 1) + '-' + endIdx + ' of ' + totalCandidates;
        }
        elementsHeader += '):';
        lines.push(elementsHeader);

        for (let i = 0; i < items.length; i++) {
            const it = items[i];
            let desc = '[' + it.ref + '] ' + it.tag;
            if (it.type) desc += '(' + it.type + ')';
            else if (it.role && it.role !== it.tag) desc += '(' + it.role + ')';
            if (it.label) desc += ' "' + it.label + '"';
            if (it.badge) desc += ' badge="' + it.badge + '"';
            if (it.value !== undefined && it.value !== '') desc += ' value="' + it.value + '"';
            if (it.checked !== undefined) desc += ' checked=' + it.checked;
            if (it.expanded !== undefined) desc += ' expanded=' + it.expanded;
            if (it.selected !== undefined) desc += ' selected=' + it.selected;
            if (it.disabled) desc += ' [disabled]';
            if (it.readonly) desc += ' [readonly]';
            if (it.href) desc += ' -> ' + it.href;
            if (it.options && it.options.length > 0) desc += ' options=[' + it.options.join(', ') + ']';
            if (!it.inViewport) desc += ' [offscreen]';
            if (options.includeCoords || showSom) {
                desc += ' @(' + it.box.x + ',' + it.box.y + ',' + it.box.width + 'x' + it.box.height + ')';
            }
            lines.push('  ' + desc);
        }

        if (totalCandidates <= offset) {
            const isMobile = !desktopSpoofEnabled && vw < 600;
            let outNotice = 'Notice: [OFFSET_OUT_OF_BOUNDS] offset=' + offset + ' exceeds total elements (' + totalCandidates + '). ';
            if (isMobile) {
                outNotice += 'Current page is in Mobile mode (' + vw + 'x' + vh + ') with only ' + totalCandidates + ' elements. If you need the full PC layout/table, use browser(action="set_mode", mode="desktop") first.';
            } else {
                outNotice += 'End of element stream reached (total=' + totalCandidates + '). Use observe(offset=' + Math.max(0, totalCandidates - limit) + ') to see the final slice.';
            }
            lines.push(outNotice);
        } else if (totalCandidates > endIdx) {
            const remaining = totalCandidates - endIdx;
            lines.push('Notice: [COLLAPSED_ELEMENTS] ' + remaining + ' elements omitted (offset=' + offset + ', limit=' + limit + ', total=' + totalCandidates + '). Use observe(offset=' + endIdx + ') to view next slice, or observe(selector="...") to inspect a specific sub-tree.');
        }

        if (slicedText) {
            let textHeader = 'Page Text Excerpt';
            if (totalTextChars > textLimit || textOffset > 0) {
                textHeader += ' (chars ' + textOffset + '-' + Math.min(totalTextChars, textOffset + slicedText.length) + ' of ' + totalTextChars + ')';
            }
            textHeader += ':';
            lines.push(textHeader);
            lines.push('  ' + slicedText);

            if (textOffset + slicedText.length < totalTextChars) {
                const remChars = totalTextChars - (textOffset + slicedText.length);
                lines.push('  [... ' + remChars + ' chars remaining. Use observe(text_offset=' + (textOffset + slicedText.length) + ') to read more]');
            }
        }

        return {
            ok: true,
            url: location.href,
            title: document.title || '',
            scope: scopedSelector,
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
            collapsed: {
                total: totalCandidates,
                offset: offset,
                limit: limit,
                shown: items.length,
                remaining: Math.max(0, totalCandidates - endIdx),
                hasMore: totalCandidates > endIdx
            },
            textExcerpt: {
                totalChars: totalTextChars,
                offset: textOffset,
                limit: textLimit,
                hasMore: textOffset + slicedText.length < totalTextChars
            },
            compactText: lines.join('\n')
        };
    }

    // 8. Fingerprint & Semantic Target Resolver (Auto-Healing & Semantic Locators)
    function findElementByFingerprint(meta) {
        if (!meta) return null;
        if (meta.id) {
            try {
                const byId = document.getElementById(meta.id);
                if (byId && byId.isConnected) return byId;
            } catch (e) {}
        }
        if (meta.href) {
            try {
                const byHref = document.querySelector('[href=' + JSON.stringify(meta.href) + ']');
                if (byHref && byHref.isConnected) return byHref;
            } catch (e) {}
        }
        if (meta.name) {
            try {
                const byName = document.querySelector('[name=' + JSON.stringify(meta.name) + ']');
                if (byName && byName.isConnected) return byName;
            } catch (e) {}
        }
        if (meta.label && meta.label.length >= 2) {
            const candidates = document.querySelectorAll(meta.tag || 'button, a, div, span, input');
            for (let i = 0; i < candidates.length; i++) {
                const c = candidates[i];
                if (!c.isConnected) continue;
                const cLabel = extractElementLabel(c).trim();
                if (cLabel === meta.label) {
                    return c;
                }
            }
        }
        return null;
    }

    function findElementByText(query, exact) {
        if (!query) return null;
        const text = String(query).trim();
        if (!text) return null;

        const interactives = document.querySelectorAll(
            'button, a, input, select, textarea, [role="button"], [role="link"], [role="tab"], [role="menuitem"], [onclick], [tabindex]'
        );
        let partialMatch = null;
        for (let i = 0; i < interactives.length; i++) {
            const el = interactives[i];
            if (!el.isConnected) continue;
            const style = window.getComputedStyle(el);
            if (style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0') continue;
            const label = extractElementLabel(el).trim();
            if (label === text) {
                return el;
            }
            if (!exact && label && label.includes(text) && !partialMatch) {
                partialMatch = el;
            }
        }
        if (partialMatch) return partialMatch;

        const textNodes = document.querySelectorAll('div, span, p, li, h1, h2, h3, h4, h5, h6, label');
        for (let i = 0; i < textNodes.length; i++) {
            const el = textNodes[i];
            if (!el.isConnected) continue;
            const style = window.getComputedStyle(el);
            if (style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0') continue;
            const label = (el.innerText || '').trim();
            if (label === text) {
                return el;
            }
            if (!exact && label && label.includes(text) && !partialMatch && label.length < text.length + 30) {
                partialMatch = el;
            }
        }
        return partialMatch;
    }

    function resolveTargetElement(opts) {
        if (!opts) return { ok: false, error: 'NO_TARGET_SPECIFIED', message: 'No target ref, text, or selector specified.' };
        if (typeof opts === 'string') {
            opts = { ref: opts };
        }

        const ref = (opts.ref || '').trim();
        const text = (opts.text || '').trim();
        const selector = (opts.selector || '').trim();
        let reResolved = false;
        let matchedBy = 'ref';
        let matchedEl = null;

        // 1. Ref match with auto-healing
        if (ref) {
            const el = refMap[ref];
            if (el && el.isConnected) {
                matchedEl = el;
            } else {
                const meta = refMetaMap[ref];
                if (meta) {
                    matchedEl = findElementByFingerprint(meta);
                    if (matchedEl) {
                        reResolved = true;
                        refMap[ref] = matchedEl;
                    }
                }
            }
        }

        // 2. Selector fallback
        if (!matchedEl && selector) {
            try {
                const el = document.querySelector(selector);
                if (el && el.isConnected) {
                    matchedEl = el;
                    matchedBy = 'selector';
                }
            } catch (ignored) {}
        }

        // 3. Text semantic fallback
        if (!matchedEl && text) {
            matchedEl = findElementByText(text, opts.exact);
            if (matchedEl) {
                matchedBy = 'text';
            }
        }

        if (!matchedEl) {
            if (ref && refMap[ref] && !refMap[ref].isConnected) {
                return {
                    ok: false,
                    error: 'STALE_ELEMENT_REF',
                    message: 'Element ' + ref + ' detached from DOM and fingerprint auto-healing could not relocate it.',
                    ref: ref
                };
            }
            return {
                ok: false,
                error: 'ELEMENT_NOT_FOUND',
                message: 'Could not resolve element with ' + (ref ? 'ref="' + ref + '" ' : '') + (text ? 'text="' + text + '" ' : '') + (selector ? 'selector="' + selector + '"' : '')
            };
        }

        return {
            ok: true,
            el: matchedEl,
            ref: ref || undefined,
            matchedBy: matchedBy,
            re_resolved: reResolved
        };
    }

    // 9. Prepare Element for Native Touch / Click
    function prepareClick(targetOpts) {
        const resolved = resolveTargetElement(targetOpts);
        if (!resolved.ok) {
            return resolved;
        }
        const el = resolved.el;
        const ref = resolved.ref || 'target';

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
        const label = extractElementLabel(el);

        const hitEl = document.elementFromPoint(cssX, cssY);
        const occluded = !!(hitEl && hitEl !== el && !el.contains(hitEl) && !hitEl.contains(el));
        const activeTagBefore = document.activeElement ? (document.activeElement.tagName || '').toLowerCase() : '';
        const activeIdBefore = document.activeElement ? (document.activeElement.id || '') : '';

        return {
            ok: true,
            ref: ref,
            label: label,
            tag: (el.tagName || '').toLowerCase(),
            cssX: cssX,
            cssY: cssY,
            vvScale: scale,
            vvOffsetLeft: offsetLeft,
            vvOffsetTop: offsetTop,
            dpr: window.devicePixelRatio || 1,
            revisionBefore: documentRevision,
            urlBefore: location.href,
            activeTagBefore: activeTagBefore,
            activeIdBefore: activeIdBefore,
            occluded: occluded,
            re_resolved: resolved.re_resolved,
            matchedBy: resolved.matchedBy,
            hit: {
                tag: el.tagName || '',
                text: cleanText(label, 60),
                occluded: occluded,
                rect: [Math.round(rect.left), Math.round(rect.top), Math.round(rect.width), Math.round(rect.height)]
            }
        };
    }

    // 10. JS Synthetic Click Fallback
    function jsClick(targetOpts) {
        const resolved = resolveTargetElement(targetOpts);
        if (!resolved.ok) {
            return resolved;
        }
        const el = resolved.el;
        const ref = resolved.ref || 'target';
        const revBefore = documentRevision;
        const urlBefore = location.href;
        const activeBefore = (document.activeElement ? (document.activeElement.tagName || '').toLowerCase() : '') +
            '#' + (document.activeElement ? (document.activeElement.id || '') : '');
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

        const revAfter = documentRevision;
        const urlAfter = location.href;
        const activeAfter = (document.activeElement ? (document.activeElement.tagName || '').toLowerCase() : '') +
            '#' + (document.activeElement ? (document.activeElement.id || '') : '');
        const domChanged = revAfter > revBefore;
        const urlChanged = urlBefore !== urlAfter;
        const focusChanged = activeBefore !== activeAfter;
        const effectChanged = domChanged || urlChanged || focusChanged;

        return {
            ok: true,
            ref: ref,
            label: extractElementLabel(el),
            strategy: 'DOM_SYNTHETIC',
            verified: effectChanged,
            revisionBefore: revBefore,
            revisionAfter: revAfter,
            re_resolved: resolved.re_resolved,
            matchedBy: resolved.matchedBy,
            hit: {
                ok: true,
                tag: el.tagName || '',
                text: cleanText(extractElementLabel(el), 60),
                occluded: false,
                strategy: 'DOM_SYNTHETIC'
            },
            effect: {
                changed: effectChanged,
                dom_mutations: Math.max(0, revAfter - revBefore),
                url_changed: urlChanged,
                focus_changed: focusChanged
            }
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

            // 1. Dispatch beforeinput (for modern React, Vue, Lexical, and Web components)
            try {
                el.dispatchEvent(new InputEvent('beforeinput', {
                    bubbles: true,
                    cancelable: true,
                    inputType: clear === false ? 'insertText' : 'insertReplacementText',
                    data: finalText
                }));
            } catch (e) {}

            // 2. Set value via prototype descriptor or direct assignment
            try {
                if (descriptor && descriptor.set) {
                    descriptor.set.call(el, finalText);
                } else {
                    el.value = finalText;
                }
            } catch (e) {
                try {
                    el.value = finalText;
                } catch (e2) {}
            }

            // 3. Reset React 16-19 internal _valueTracker so controlled inputs detect the change
            if (el._valueTracker) {
                try {
                    el._valueTracker.setValue(previousValue);
                } catch (e) {}
            }

            // 4. Dispatch standard input & change events
            try {
                el.dispatchEvent(new Event('input', { bubbles: true, cancelable: true }));
                el.dispatchEvent(new Event('change', { bubbles: true, cancelable: true }));
            } catch (e) {}
            return true;
        } else if (el.isContentEditable || el.getAttribute('contenteditable') === 'true' || el.getAttribute('role') === 'textbox') {
            try {
                el.focus();
            } catch (e) {}

            // 1. Dispatch beforeinput
            try {
                el.dispatchEvent(new InputEvent('beforeinput', {
                    bubbles: true,
                    cancelable: true,
                    inputType: clear === false ? 'insertText' : 'insertReplacementText',
                    data: finalText
                }));
            } catch (e) {}

            let applied = false;
            try {
                if (clear !== false) {
                    document.execCommand('selectAll', false, null);
                }
                applied = document.execCommand('insertText', false, finalText);
            } catch (e) {}

            const currentVal = (el.innerText !== undefined ? el.innerText : (el.textContent || '')).trim();
            if (!applied || (!currentVal.includes(finalText.trim()) && currentVal !== finalText)) {
                try {
                    if (clear === false) {
                        el.innerText = (el.innerText || '') + finalText;
                    } else {
                        el.innerText = finalText;
                    }
                } catch (e) {}
            }

            try {
                el.dispatchEvent(new Event('input', { bubbles: true }));
                el.dispatchEvent(new Event('change', { bubbles: true }));
            } catch (e) {}
            return true;
        }
        return false;
    }

    function fill(targetOpts, text, clear, submit) {
        const resolved = resolveTargetElement(targetOpts);
        if (!resolved.ok) {
            return resolved;
        }
        const el = resolved.el;
        const ref = resolved.ref || 'target';
        const revBefore = documentRevision;

        // 1. Pre-check: Reject disabled elements
        if (el.disabled || el.getAttribute('aria-disabled') === 'true') {
            return {
                ok: false,
                error: 'ELEMENT_DISABLED',
                ref: ref,
                message: 'Element ' + ref + ' is disabled and cannot accept input.'
            };
        }

        // 2. Pre-check: Reject read-only elements
        if (el.readOnly || el.getAttribute('aria-readonly') === 'true' || el.getAttribute('readonly') !== null) {
            return {
                ok: false,
                error: 'ELEMENT_READONLY',
                ref: ref,
                message: 'Element ' + ref + ' is read-only and cannot be modified.'
            };
        }

        // 3. Pre-check: Reject non-fillable input types (file, checkbox, radio, buttons)
        const tag = (el.tagName || '').toLowerCase();
        if (tag === 'input') {
            const inputType = (el.type || 'text').toLowerCase();
            if (inputType === 'file') {
                return {
                    ok: false,
                    error: 'FILE_INPUT_NOT_SUPPORTED',
                    ref: ref,
                    message: 'Element ' + ref + ' is a file input. Direct text fill is blocked by browser security.'
                };
            }
            if (inputType === 'checkbox' || inputType === 'radio') {
                return {
                    ok: false,
                    error: 'INVALID_INPUT_TYPE',
                    ref: ref,
                    message: 'Element ' + ref + ' is a ' + inputType + '. Use click instead of fill.'
                };
            }
            if (inputType === 'button' || inputType === 'submit' || inputType === 'reset') {
                return {
                    ok: false,
                    error: 'BUTTON_NOT_EDITABLE',
                    ref: ref,
                    message: 'Element ' + ref + ' is a ' + inputType + ' button. Use click instead of fill.'
                };
            }
        }

        // 4. Pre-check: Target must be input, textarea, or contenteditable
        const isContentEditable = !!(el.isContentEditable || el.getAttribute('contenteditable') === 'true' || el.getAttribute('role') === 'textbox');
        if (tag !== 'input' && tag !== 'textarea' && !isContentEditable) {
            return {
                ok: false,
                error: 'NOT_EDITABLE',
                ref: ref,
                message: 'Element ' + ref + ' (<' + tag + '>) is not an input, textarea, or contenteditable element.'
            };
        }

        try {
            el.scrollIntoView({ block: 'center', inline: 'center', behavior: 'instant' });
        } catch (e) {}

        const expectedText = clear === false ? ((el.value || el.innerText || '') + text) : String(text);
        const applied = setSingleFieldValue(el, text, clear !== false);
        if (!applied) {
            return {
                ok: false,
                error: 'NOT_EDITABLE',
                ref: ref,
                message: 'Element ' + ref + ' failed to accept input setter.'
            };
        }

        // 5. Readback Verification: Read actual value from the element and compare with expected input
        let actualValue = '';
        if (tag === 'input' || tag === 'textarea') {
            actualValue = el.value !== undefined ? String(el.value) : '';
        } else {
            actualValue = (el.innerText !== undefined ? el.innerText : (el.textContent || '')).trim();
        }

        const isSensitive = isSensitiveInput(el);
        let verified = false;

        if (isSensitive) {
            // Sensitive fields (passwords): verify value is present if non-empty expected, mask in receipt
            verified = (expectedText === '') ? (actualValue === '') : (actualValue.length > 0);
        } else if (expectedText === '') {
            // Clearing field
            verified = (actualValue === '');
        } else {
            // Robust multi-mode matching:
            // - Exact match: actualValue === expectedText
            // - Trimmed match: actualValue.trim() === expectedText.trim()
            // - Whitespace-normalized match (handles rich text editors inserting non-breaking spaces or linebreaks)
            // - Substring containment (for auto-formatting inputs e.g. phone masks "+86 138-0000" containing "1380000")
            // - MaxLength truncation match
            const normActual = actualValue.replace(/\s+/g, ' ').trim();
            const normExpected = expectedText.replace(/\s+/g, ' ').trim();
            const compactActual = actualValue.replace(/[\s\-_()+]/g, '');
            const compactExpected = expectedText.replace(/[\s\-_()+]/g, '');

            if (actualValue === expectedText || normActual === normExpected) {
                verified = true;
            } else if (compactActual === compactExpected || compactActual.includes(compactExpected)) {
                verified = true;
            } else if (normActual.includes(normExpected) || actualValue.includes(expectedText)) {
                verified = true;
            } else if (el.maxLength > 0 && normActual === normExpected.substring(0, el.maxLength)) {
                verified = true;
            }
        }

        if (!verified) {
            return {
                ok: false,
                error: 'VERIFICATION_FAILED',
                ref: ref,
                expected: isSensitive ? '***' : (expectedText.length > 80 ? expectedText.substring(0, 77) + '...' : expectedText),
                actual: isSensitive ? '***' : (actualValue.length > 80 ? actualValue.substring(0, 77) + '...' : actualValue),
                message: 'Fill verification failed on ' + ref + ': expected "' +
                    (isSensitive ? '***' : expectedText) + '", but read back "' + (isSensitive ? '***' : actualValue) + '".'
            };
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
            value: isSensitive ? '***' : actualValue,
            verified: true,
            submitted: !!submit,
            revisionBefore: revBefore,
            revisionAfter: documentRevision,
            re_resolved: resolved.re_resolved,
            matchedBy: resolved.matchedBy
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
            results.push(fill(f, f.text || f.value || '', f.clear !== false, isLast || !!f.submit));
        }
        return { ok: true, results: results, revision: documentRevision };
    }

    // 11. Select Option in <select> Dropdown
    function selectOption(targetOpts, valueOrLabel) {
        const resolved = resolveTargetElement(targetOpts);
        if (!resolved.ok) {
            return resolved;
        }
        const el = resolved.el;
        const ref = resolved.ref || 'target';
        if ((el.tagName || '').toLowerCase() !== 'select') {
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
            selectedText: el.options[matchedIdx].text,
            re_resolved: resolved.re_resolved,
            matchedBy: resolved.matchedBy
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
            activeTag: document.activeElement ? (document.activeElement.tagName || '').toLowerCase() : '',
            activeId: document.activeElement ? (document.activeElement.id || '') : '',
            somEnabled: somEnabled,
            desktopSpoofEnabled: desktopSpoofEnabled
        };
    }

    let waitBaselineStartTime = 0;

    function checkCondition(cond, target, quietMs, isFirst) {
        cond = (cond || '').toLowerCase();
        const now = Date.now();
        const effectiveQuietMs = Math.max(50, typeof quietMs === 'number' ? quietMs : 300);

        if (isFirst || !waitBaselineStartTime) {
            waitBaselineStartTime = now;
        }

        const elapsedSinceStart = now - waitBaselineStartTime;
        const elapsedSinceMutation = now - lastMutationTime;

        if (cond === 'stable') {
            const windowElapsedSatisfied = elapsedSinceStart >= effectiveQuietMs;
            const quietSatisfied = elapsedSinceMutation >= effectiveQuietMs;
            const satisfied = windowElapsedSatisfied && quietSatisfied;

            return {
                ok: true,
                satisfied: satisfied,
                msSinceMutation: elapsedSinceMutation,
                windowElapsed: elapsedSinceStart,
                quietMs: effectiveQuietMs,
                revision: documentRevision
            };
        }

        if (cond === 'networkidle' || cond === 'load' || cond === 'complete') {
            const ready = document.readyState === 'complete';
            const windowElapsedSatisfied = elapsedSinceStart >= effectiveQuietMs;
            const quietSatisfied = elapsedSinceMutation >= effectiveQuietMs;
            return {
                ok: true,
                satisfied: ready && windowElapsedSatisfied && quietSatisfied,
                readyState: document.readyState,
                msSinceMutation: elapsedSinceMutation,
                windowElapsed: elapsedSinceStart,
                quietMs: effectiveQuietMs
            };
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
        setSomEnabled: setSomHighlight,
        enforceDesktopViewport: enforceDesktopViewport,
        clearSomOverlay: clearSomOverlay,
        getState: getState,
        checkCondition: checkCondition
    };
})();
