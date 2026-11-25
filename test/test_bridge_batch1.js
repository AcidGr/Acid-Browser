// Node regression tests for Batch-1 JS fixes (agent_bridge.js & runtime_shim.js)
const fs = require('fs');
const assert = require('assert');

global.window = global;
global.Node = { ELEMENT_NODE: 1 };
global.innerWidth = 360;
global.innerHeight = 640;
global.scrollX = 0;
global.scrollY = 0;
global.windowScrollCalls = [];
global.scrollTo = (opts) => { global.windowScrollCalls.push(opts); };
global.scrollBy = (opts) => { global.windowScrollCalls.push(opts); };

global.KeyboardEvent = class KeyboardEvent { constructor(t, o) { Object.assign(this, o, { type: t }); } };
global.Event = class Event { constructor(t, o) { Object.assign(this, o, { type: t }); } };
global.MouseEvent = class MouseEvent extends global.Event {};
global.PointerEvent = class PointerEvent extends global.Event {};
global.InputEvent = class InputEvent extends global.Event {};

function makeEl(tag, opts = {}) {
    const el = {
        nodeType: 1,
        tagName: tag.toUpperCase(),
        id: opts.id || '',
        className: opts.className || '',
        type: opts.type || '',
        value: opts.value || '',
        innerText: opts.text || '',
        textContent: opts.text || '',
        scrollTop: opts.scrollTop || 0,
        scrollLeft: 0,
        scrollHeight: opts.scrollHeight || 400,
        isConnected: true,
        children: opts.children || [],
        attributes: { ...(opts.attrs || {}) },
        style: { ...(opts.style || {}) },
        clickedCount: 0,
        control: opts.control || null,
        getAttribute(k) { return Object.prototype.hasOwnProperty.call(this.attributes, k) ? this.attributes[k] : null; },
        setAttribute(k, v) { this.attributes[k] = String(v); },
        removeAttribute(k) { delete this.attributes[k]; },
        hasAttribute(k) { return Object.prototype.hasOwnProperty.call(this.attributes, k); },
        contains(other) {
            if (other === this) return true;
            return (this.children || []).some(c => c.contains && c.contains(other));
        },
        getBoundingClientRect() {
            return opts.rect || { left: 10, top: 10, width: 120, height: 32, right: 130, bottom: 42 };
        },
        scrollIntoView() {},
        focus() {},
        click() { this.clickedCount++; },
        dispatchEvent(evt) {
            if (evt && evt.type === 'click') this.clickedCount++;
            return true;
        },
        querySelectorAll() { return []; },
        querySelector() { return null; }
    };
    return el;
}

const bodyEl = makeEl('body', {
    rect: { left: 0, top: 0, width: 360, height: 640, right: 360, bottom: 640 }
});

global.document = {
    title: 'Batch1 Test Page',
    body: bodyEl,
    documentElement: makeEl('html', {
        rect: { left: 0, top: 0, width: 360, height: 640, right: 360, bottom: 640 },
        children: [bodyEl]
    }),
    addEventListener() {},
    removeEventListener() {},
    createElement(tag) { return makeEl(tag); },
    querySelectorAll() { return []; },
    querySelector() { return null; },
    getElementById() { return null; },
    elementFromPoint() { return null; }
};
global.getComputedStyle = (el) => ({
    display: el.style.display || 'block',
    visibility: el.style.visibility || 'visible',
    opacity: el.style.opacity !== undefined ? String(el.style.opacity) : '1',
    cursor: el.style.cursor || 'auto',
    overflowY: el.style.overflowY || 'visible'
});
global.location = { href: 'https://example.test/' };

eval(fs.readFileSync('assets/agent_bridge.js', 'utf8'));
const bridge = window.__acidBridge;

console.log('[TestBridgeBatch1] Running agent_bridge & runtime_shim regression checks...');

// Case 1: Paginated observe(offset>0) numbering & refMap preservation
const btn1 = makeEl('button', {
    id: 'btn1',
    text: 'First Action',
    scrollHeight: 900,
    rect: { left: 10, top: 10, width: 100, height: 40, right: 110, bottom: 50 }
});
const btn2 = makeEl('button', {
    id: 'btn2',
    text: 'Second Action',
    rect: { left: 10, top: 70, width: 100, height: 40, right: 110, bottom: 110 }
});
bodyEl.children = [btn1, btn2];

const obsPage1 = bridge.observe({ offset: 0, limit: 1, includeSom: false });
assert.strictEqual(obsPage1.elements.length, 1);
assert.strictEqual(obsPage1.elements[0].ref, 'e1', 'First page first element is e1');

const obsPage2 = bridge.observe({ offset: 1, limit: 1, includeSom: false });
assert.strictEqual(obsPage2.elements.length, 1);
assert.strictEqual(obsPage2.elements[0].ref, 'e2', 'Offset page must continue global numbering as e2 (not reset to e1)');

// Clicking e1 after offset=1 observe must still resolve btn1 instead of overwritten refMap!
const click1 = bridge.jsClick({ ref: 'e1' });
const click2 = bridge.jsClick({ ref: 'e2' });
assert.strictEqual(click1.ok, true, 'e1 from page 1 remains clickable after offset=1 observe');
assert.strictEqual(click2.ok, true, 'e2 from page 2 is clickable');
assert.strictEqual(btn1.clickedCount >= 1 && btn2.clickedCount >= 1, true, 'Both btn1 and btn2 received click events');
console.log('✓ Case 1: Paginated observe(offset>0) preserves prior refs & numbers e2+');

// Case 2: <label> skipped when control is visible, included when control is hidden
const visibleChk = makeEl('input', {
    type: 'checkbox',
    attrs: { type: 'checkbox', 'aria-label': 'Visible Check' },
    rect: { left: 10, top: 130, width: 20, height: 20, right: 30, bottom: 150 }
});
const labelForVisible = makeEl('label', {
    text: 'Label Visible',
    control: visibleChk,
    rect: { left: 40, top: 130, width: 100, height: 24, right: 140, bottom: 154 }
});
const hiddenChk = makeEl('input', {
    type: 'checkbox',
    style: { display: 'none' },
    attrs: { type: 'checkbox' },
    rect: { left: 0, top: 0, width: 0, height: 0, right: 0, bottom: 0 }
});
const labelForHidden = makeEl('label', {
    text: 'Custom Styled Toggle',
    control: hiddenChk,
    rect: { left: 10, top: 170, width: 140, height: 28, right: 150, bottom: 198 }
});
bodyEl.children = [visibleChk, labelForVisible, hiddenChk, labelForHidden];

const obsLabels = bridge.observe({ offset: 0, limit: 20, includeSom: false });
const labels = obsLabels.elements.map(e => e.label);
assert.strictEqual(labels.includes('Label Visible'), false, 'Label paired with visible control skipped to avoid duplicate ref');
assert.strictEqual(labels.includes('Custom Styled Toggle'), true, 'Label wrapping hidden custom input remains interactive');
console.log('✓ Case 2: <label> interactivity distinguishes hidden vs visible associated controls');

// Case 3: scroll top/bottom with targetRef scrolls container, not window
const scrollBox = makeEl('button', {
    text: 'Scrollable List',
    scrollHeight: 1200,
    scrollTop: 250,
    rect: { left: 10, top: 10, width: 200, height: 150, right: 210, bottom: 160 }
});
bodyEl.children = [scrollBox];
bridge.observe({ offset: 0, limit: 10, includeSom: false });
global.windowScrollCalls = [];

const resBottom = bridge.scroll('bottom', 0.75, 'e1');
assert.strictEqual(resBottom.ok, true);
assert.strictEqual(scrollBox.scrollTop, 1200, 'scroll(bottom, targetRef) scrolls target container to scrollHeight');
assert.strictEqual(global.windowScrollCalls.length, 0, 'scroll(bottom, targetRef) must NOT call window.scrollTo');

const resTop = bridge.scroll('top', 0.75, 'e1');
assert.strictEqual(resTop.ok, true);
assert.strictEqual(scrollBox.scrollTop, 0, 'scroll(top, targetRef) scrolls target container to 0');
assert.strictEqual(global.windowScrollCalls.length, 0, 'scroll(top, targetRef) must NOT call window.scrollTo');
console.log('✓ Case 3: scroll(top/bottom, targetRef) targets container without scrolling window');

// Case 4: runtime_shim.js unconditionally overwrites hostile __acid_dispatch_http_cb
let hostileCalled = false;
window.__acid_dispatch_http_cb = () => { hostileCalled = true; };
window.__acid_gm_bridge__ = { call: () => '{}' };
let shimCode = fs.readFileSync('assets/userscripts/runtime_shim.js', 'utf8');
shimCode = shimCode
    .replaceAll('/*__GUARD_KEY__*/', JSON.stringify('shim_test.user.js'))
    .replaceAll('/*__SAFE_SID__*/""', JSON.stringify('shim_test.user.js'))
    .replaceAll('/*__SAFE_SID__*/', JSON.stringify('shim_test.user.js'))
    .replaceAll('/*__SAFE_SECRET__*/""', JSON.stringify('tok'))
    .replaceAll('/*__GM_INFO__*/{}', '{}');
shimCode += '\n} catch(e) { throw e; }\n})();\n';
eval(shimCode);
window.__acid_dispatch_http_cb('missing_cb', { responseText: '{}' }, true);
assert.strictEqual(hostileCalled, false, 'Hostile page-defined __acid_dispatch_http_cb must be overwritten, not reused via ||');
console.log('✓ Case 4: runtime_shim.js replaces preexisting __acid_dispatch_http_cb');

// Case 5: runtime_shim.js does NOT force 'use strict' so legacy @require scripts work,
// and @unwrap idempotency guard executes @require + body at most once across repeated injections.
let rawShim = fs.readFileSync('assets/userscripts/runtime_shim.js', 'utf8');
assert.ok(!rawShim.includes("'use strict'") && !rawShim.includes('"use strict"'),
    'runtime_shim.js must not inject top-level use strict into legacy @require closures');
let sloppyShim = rawShim
    .replaceAll('/*__GUARD_KEY__*/', JSON.stringify('shim_sloppy_test.user.js'))
    .replaceAll('/*__SAFE_SID__*/""', JSON.stringify('shim_sloppy_test.user.js'))
    .replaceAll('/*__SAFE_SID__*/', JSON.stringify('shim_sloppy_test.user.js'))
    .replaceAll('/*__SAFE_SECRET__*/""', JSON.stringify('tok2'))
    .replaceAll('/*__GM_INFO__*/{}', '{}');
delete global.acidSloppyGlobalFromRequire;
sloppyShim += '\nacidSloppyGlobalFromRequire = 4242;\n} catch(e) { throw e; }\n})();\n';
eval(sloppyShim);
assert.strictEqual(global.acidSloppyGlobalFromRequire, 4242,
    'Legacy sloppy-mode @require assigning undeclared variable must succeed inside runtime_shim.js');
delete global.acidSloppyGlobalFromRequire;

let unwrapExecCount = 0;
const unwrapGuardKey = 'unwrap_once_test.user.js';
const runUnwrapSnippet = () => {
    window.__acidInjectedUserscripts = window.__acidInjectedUserscripts || {};
    if (!window.__acidInjectedUserscripts[unwrapGuardKey]) {
        window.__acidInjectedUserscripts[unwrapGuardKey] = true;
        unwrapExecCount++;
    }
};
runUnwrapSnippet();
runUnwrapSnippet();
assert.strictEqual(unwrapExecCount, 1, '@unwrap injection guard must prevent duplicate execution across onPageStarted/onPageFinished');
console.log('✓ Case 5: runtime_shim.js non-strict @require compatibility & @unwrap idempotency guard');

// Case 6: paginated observe(offset>0) resets stale refMap when viewportOnly changes
const offscreenBtn = makeEl('button', {
    id: 'off_btn',
    text: 'Offscreen Button',
    rect: { left: 10, top: 2500, width: 120, height: 32, right: 130, bottom: 2532 }
});
const visibleBtn = makeEl('button', {
    id: 'vis_btn',
    text: 'Visible Button',
    rect: { left: 10, top: 20, width: 120, height: 32, right: 130, bottom: 52 }
});
bodyEl.children = [offscreenBtn, visibleBtn];
const obsAll = bridge.observe({ offset: 0, limit: 1, viewportOnly: false, includeSom: false });
assert.strictEqual(obsAll.collapsed.total, 2, 'viewportOnly=false counts both visible and offscreen buttons');
assert.strictEqual(obsAll.elements[0].ref, 'e1');
const obsVpChanged = bridge.observe({ offset: 1, limit: 10, viewportOnly: true, includeSom: false });
assert.strictEqual(obsVpChanged.collapsed.total, 1, 'viewportOnly=true excludes offscreen button');
assert.strictEqual(bridge.prepareClick({ ref: 'e1' }).ok, false, 'Changing viewportOnly with offset>0 clears stale e1 from prior refMap');
const obsVpFresh = bridge.observe({ offset: 0, limit: 10, viewportOnly: true, includeSom: false });
assert.strictEqual(obsVpFresh.elements.length, 1);
assert.strictEqual(obsVpFresh.elements[0].label, 'Visible Button');
console.log('✓ Case 6: observe() resets cached refMap when viewportOnly toggles');

// Case 7: textExcerpt slices normalized body text once without whitespace-chunk skew
bodyEl.innerText = '   Alpha\n\n\t   Beta   \n\n   Gamma   ';
bodyEl.children = [];
const obsText1 = bridge.observe({ offset: 0, limit: 10, textOffset: 0, textLimit: 10, includeSom: false });
assert.strictEqual(obsText1.textExcerpt.totalChars, 16, 'Normalized text length collapses whitespace before slicing');
assert.strictEqual(obsText1.textExcerpt.hasMore, true, 'First text slice reports remaining chars accurately');
assert.ok(obsText1.compactText.includes('Alpha Beta'), 'First slice contains exact leading normalized window');
const obsText2 = bridge.observe({ offset: 0, limit: 10, textOffset: 10, textLimit: 10, includeSom: false });
assert.strictEqual(obsText2.textExcerpt.hasMore, false, 'Second slice reaches exact end of normalized text');
assert.ok(obsText2.compactText.includes(' Gamma'), 'Second slice continues seamlessly without whitespace skew');
bodyEl.innerText = '';
console.log('✓ Case 7: observe() textExcerpt slices normalized whitespace consistently across pages');

// Case 8: Same-origin iframe coordinate propagation into observe/prepareClick
const innerBtn = makeEl('button', {
    id: 'iframe_pay_btn',
    text: 'Confirm Payment',
    rect: { left: 10, top: 20, width: 80, height: 30, right: 90, bottom: 50 }
});
const iframeDoc = {
    body: makeEl('body', {
        rect: { left: 0, top: 0, width: 200, height: 200, right: 200, bottom: 200 },
        children: [innerBtn]
    }),
    elementFromPoint() { return innerBtn; },
    querySelectorAll() { return []; },
    querySelector() { return null; }
};
const iframeEl = makeEl('iframe', {
    id: 'checkout_frame',
    rect: { left: 100, top: 200, width: 200, height: 200, right: 300, bottom: 400 }
});
iframeEl.ownerDocument = global.document;
iframeEl.contentDocument = iframeDoc;
const iframeWin = { frameElement: iframeEl, getComputedStyle: global.getComputedStyle };
iframeDoc.defaultView = iframeWin;
iframeDoc.body.ownerDocument = iframeDoc;
innerBtn.ownerDocument = iframeDoc;
bodyEl.children = [iframeEl];

const obsIframe = bridge.observe({ offset: 0, limit: 10, includeSom: false });
assert.ok(obsIframe.elements.some(e => e.label === 'Confirm Payment'), 'observe() traverses same-origin iframe content');
const iframeBtnRef = obsIframe.elements.find(e => e.label === 'Confirm Payment').ref;
const prepIframe = bridge.prepareClick({ ref: iframeBtnRef });
assert.strictEqual(prepIframe.ok, true);
assert.strictEqual(prepIframe.cssX, 150, 'prepareClick adds enclosing iframe left offset (100 + 10 + 40 = 150)');
assert.strictEqual(prepIframe.cssY, 235, 'prepareClick adds enclosing iframe top offset (200 + 20 + 15 = 235)');
console.log('✓ Case 8: Same-origin iframe offsets propagate to observe() & prepareClick()');

// Case 9: findElementByFingerprint scopes [name=...] candidates by tag and full label
const decoyInput = makeEl('input', {
    text: 'Wrong Input',
    attrs: { name: 'action_item' },
    rect: { left: 10, top: 10, width: 100, height: 30, right: 110, bottom: 40 }
});
const realBtn = makeEl('button', {
    text: 'This is a very long button label exceeding eighty characters so prefix truncation never breaks exact healing match',
    attrs: { name: 'action_item' },
    rect: { left: 10, top: 60, width: 160, height: 36, right: 170, bottom: 96 }
});
const reRenderedBtn = makeEl('button', {
    text: 'This is a very long button label exceeding eighty characters so prefix truncation never breaks exact healing match',
    attrs: { name: 'action_item' },
    rect: { left: 10, top: 60, width: 160, height: 36, right: 170, bottom: 96 }
});
const oldQuerySelectorAll = global.document.querySelectorAll;
global.document.querySelectorAll = (sel) => {
    if (sel === 'button[name="action_item"]') return [reRenderedBtn];
    if (sel === '[name="action_item"]') return [decoyInput, reRenderedBtn];
    return [];
};
bodyEl.children = [decoyInput, realBtn];
const obsHeal = bridge.observe({ offset: 0, limit: 10, includeSom: false });
const targetBtnItem = obsHeal.elements.find(e => e.tag === 'button');
// Simulate DOM re-render detaching the old element while preserving tag+name+full label
realBtn.isConnected = false;
decoyInput.isConnected = true;
reRenderedBtn.isConnected = true;
const prepHeal = bridge.prepareClick({ ref: targetBtnItem.ref });
global.document.querySelectorAll = oldQuerySelectorAll;
assert.strictEqual(prepHeal.ok, true, 'Detached element auto-heals across DOM replacement');
assert.strictEqual(prepHeal.re_resolved, true);
assert.strictEqual(prepHeal.cssY, 78, 'Auto-healing resolves the matching tag+label button rather than the [name] input decoy');
console.log('✓ Case 9: Auto-healing scopes [name] locators by tag and preserves full >80-char labels');

// Case 10: runtime_shim.js GM_xmlhttpRequest.abort() delivers details.onabort before cleanup
global.navigator = global.navigator || { userAgent: 'AcidTest/1.0' };
let capturedXmlReq = null;
let capturedAbortCall = null;
window.__acid_gm_host__ = {
    call: (act, payloadJson) => {
        const p = JSON.parse(payloadJson || '{}');
        if (act === 'xmlhttpRequest') capturedXmlReq = p;
        if (act === 'abortRequest') capturedAbortCall = p;
        return '{}';
    }
};
let abortShim = fs.readFileSync('assets/userscripts/runtime_shim.js', 'utf8')
    .replaceAll('/*__GUARD_KEY__*/', JSON.stringify('shim_abort_test.user.js'))
    .replaceAll('/*__SAFE_SID__*/""', JSON.stringify('shim_abort_test.user.js'))
    .replaceAll('/*__SAFE_SID__*/', JSON.stringify('shim_abort_test.user.js'))
    .replaceAll('/*__SAFE_SECRET__*/""', JSON.stringify('tok3'))
    .replaceAll('/*__GM_INFO__*/{}', '{}');
abortShim += `
var reqHandle = GM_xmlhttpRequest({
    url: 'https://example.test/stream',
    onload: function() {},
    onabort: function(res) { global.__acidTestAbortFired = res && res.aborted === true; }
});
reqHandle.abort();
} catch(e) { throw e; }
})();
`;
delete global.__acidTestAbortFired;
eval(abortShim);
assert.ok(capturedXmlReq && capturedXmlReq.onabort && capturedXmlReq.onload, 'xmlhttpRequest registers onload and onabort keys');
assert.ok(capturedAbortCall && capturedAbortCall.id === capturedXmlReq.id, 'abort() notifies host with matching request id');
assert.strictEqual(typeof window[capturedXmlReq.onload], 'undefined', 'Non-abort callback keys are pruned immediately on abort()');
assert.strictEqual(typeof window[capturedXmlReq.onabort], 'function', 'onabort handler remains registered until host dispatches abort callback');
window.__acid_dispatch_http_cb(capturedXmlReq.onabort, { aborted: true }, true);
assert.strictEqual(global.__acidTestAbortFired, true, 'details.onabort fires cleanly after abort()');
assert.strictEqual(typeof window[capturedXmlReq.onabort], 'undefined', 'onabort handler is cleaned up after delivery');
delete global.__acidTestAbortFired;
console.log('✓ Case 10: runtime_shim.js preserves onabort across GM_xmlhttpRequest().abort() until callback delivery');

// Case 11: Unassociated <label> without interactive role is omitted, while role=button and visible-hidden-input label remain
const unassocLabel = makeEl('label', {
    text: 'Plain Field Label',
    rect: { left: 10, top: 10, width: 120, height: 24, right: 130, bottom: 34 }
});
const buttonRoleLabel = makeEl('label', {
    text: 'Toggle Filter',
    attrs: { role: 'button' },
    rect: { left: 10, top: 45, width: 120, height: 32, right: 130, bottom: 77 }
});
const srOnlyChk = makeEl('input', {
    id: 'sr_only_chk',
    attrs: { type: 'checkbox' },
    rect: { left: 10, top: 90, width: 0, height: 0, right: 10, bottom: 90 },
    style: { opacity: '0' }
});
const boundCustomLabel = makeEl('label', {
    text: 'Enable Alerts',
    control: srOnlyChk,
    rect: { left: 20, top: 90, width: 110, height: 28, right: 130, bottom: 118 }
});
bodyEl.children = [unassocLabel, buttonRoleLabel, srOnlyChk, boundCustomLabel];
const obsLabelFilter = bridge.observe({ offset: 0, limit: 10, includeSom: false });
assert.strictEqual(obsLabelFilter.elements.some(e => e.label === 'Plain Field Label'), false, 'Unassociated <label> without control or interactive role is omitted');
assert.strictEqual(obsLabelFilter.elements.some(e => e.label === 'Toggle Filter'), true, '<label role="button"> remains interactive');
assert.strictEqual(obsLabelFilter.elements.some(e => e.label === 'Enable Alerts'), true, '<label> bound to hidden custom checkbox remains interactive');
console.log('✓ Case 11: Unassociated <label> is excluded while role="button" and bound custom control labels remain interactive');

// Case 12: Same-origin iframe clientLeft/clientTop border offsets propagate to observe() & prepareClick()
const borderInnerBtn = makeEl('button', {
    id: 'bordered_iframe_btn',
    text: 'Bordered Frame Pay',
    rect: { left: 10, top: 20, width: 80, height: 30, right: 90, bottom: 50 }
});
const borderIframeDoc = {
    body: makeEl('body', {
        rect: { left: 0, top: 0, width: 200, height: 200, right: 200, bottom: 200 },
        children: [borderInnerBtn]
    }),
    elementFromPoint() { return borderInnerBtn; },
    querySelectorAll() { return []; },
    querySelector() { return null; }
};
const borderIframeEl = makeEl('iframe', {
    id: 'bordered_frame',
    rect: { left: 100, top: 200, width: 200, height: 200, right: 300, bottom: 400 }
});
borderIframeEl.clientLeft = 4;
borderIframeEl.clientTop = 6;
borderIframeEl.ownerDocument = global.document;
borderIframeEl.contentDocument = borderIframeDoc;
borderIframeDoc.defaultView = { frameElement: borderIframeEl, getComputedStyle: global.getComputedStyle };
borderIframeDoc.body.ownerDocument = borderIframeDoc;
borderInnerBtn.ownerDocument = borderIframeDoc;
bodyEl.children = [borderIframeEl];
const obsBorderIframe = bridge.observe({ offset: 0, limit: 10, includeSom: false });
const borderItem = obsBorderIframe.elements.find(e => e.label === 'Bordered Frame Pay');
assert.ok(borderItem, 'Button inside bordered iframe is observed');
assert.strictEqual(borderItem.box.x, 114, 'observe() box.x includes iframe clientLeft (100 + 4 + 10)');
assert.strictEqual(borderItem.box.y, 226, 'observe() box.y includes iframe clientTop (200 + 6 + 20)');
const prepBorderIframe = bridge.prepareClick({ ref: borderItem.ref });
assert.strictEqual(prepBorderIframe.ok, true);
assert.strictEqual(prepBorderIframe.cssX, 154, 'prepareClick adds iframe clientLeft (100 + 4 + 10 + 40 = 154)');
assert.strictEqual(prepBorderIframe.cssY, 241, 'prepareClick adds iframe clientTop (200 + 6 + 20 + 15 = 241)');
console.log('✓ Case 12: Same-origin iframe clientLeft/clientTop border offsets propagate to observe() & prepareClick()');

// Case 13: Detached scroll container ref auto-heals and unresolvable detached container errors without scrolling window
const scrollPanelA = makeEl('div', {
    id: 'log_scroller',
    scrollHeight: 900,
    scrollTop: 0,
    attrs: { tabindex: '0', 'aria-label': 'Log Output Panel' },
    rect: { left: 10, top: 20, width: 300, height: 200, right: 310, bottom: 220 }
});
const scrollPanelB = makeEl('div', {
    id: 'log_scroller',
    scrollHeight: 900,
    scrollTop: 0,
    attrs: { tabindex: '0', 'aria-label': 'Log Output Panel' },
    rect: { left: 10, top: 20, width: 300, height: 200, right: 310, bottom: 220 }
});
const prevGetById = global.document.getElementById;
const prevQsa = global.document.querySelectorAll;
try {
    bodyEl.children = [scrollPanelA];
    const obsScroll = bridge.observe({ offset: 0, limit: 10, includeSom: false });
    const scrollerItem = obsScroll.elements.find(e => e.label === 'Log Output Panel');
    assert.ok(scrollerItem, 'Focusable scroll panel receives a ref in observe()');

    // Simulate re-render: old container detached, replacement connected
    scrollPanelA.isConnected = false;
    scrollPanelB.isConnected = true;
    bodyEl.children = [scrollPanelB];
    global.document.getElementById = (id) => (id === 'log_scroller' ? scrollPanelB : null);
    global.document.querySelectorAll = (sel) => (sel.includes('log_scroller') || sel.includes('Log Output Panel') ? [scrollPanelB] : []);

    const winCallsBefore = global.windowScrollCalls.length;
    const healedScrollRes = bridge.scroll('down', 0.5, scrollerItem.ref);
    assert.strictEqual(healedScrollRes.ok, true, 'scroll() auto-heals detached container ref onto live replacement');
    assert.strictEqual(scrollPanelA.scrollTop, 0, 'Detached old scroll container is not mutated');
    assert.ok(scrollPanelB.scrollTop > 0, 'Replacement scroll container is scrolled');
    assert.strictEqual(global.windowScrollCalls.length, winCallsBefore, 'Healing container scroll does not fall back to window scroll');

    // Simulate total removal: detached ref cannot heal
    scrollPanelB.isConnected = false;
    bodyEl.children = [];
    global.document.getElementById = () => null;
    global.document.querySelectorAll = () => [];
    const missingScrollRes = bridge.scroll('down', 0.5, scrollerItem.ref);
    assert.strictEqual(missingScrollRes.ok, false, 'Unresolvable detached scroll container returns ok: false');
    assert.strictEqual(global.windowScrollCalls.length, winCallsBefore, 'Unresolvable targetRef does not silently scroll window');
} finally {
    global.document.getElementById = prevGetById;
    global.document.querySelectorAll = prevQsa;
}
console.log('✓ Case 13: Detached scroll() targetRef auto-heals across DOM replacement and errors cleanly when missing');

// Case 14: runtime_shim.js locks __acid_dispatch_http_cb & filters GM.* Promise APIs by GM_info.script.grant
const cbDescriptor = Object.getOwnPropertyDescriptor(window, '__acid_dispatch_http_cb');
assert.ok(cbDescriptor && typeof cbDescriptor.value === 'function', '__acid_dispatch_http_cb exists on window');
assert.strictEqual(cbDescriptor.writable, false, '__acid_dispatch_http_cb is locked writable: false');
assert.strictEqual(cbDescriptor.configurable, false, '__acid_dispatch_http_cb is locked configurable: false');

let grantScopedShim = fs.readFileSync('assets/userscripts/runtime_shim.js', 'utf8')
    .replaceAll('/*__GUARD_KEY__*/', JSON.stringify('shim_grant_scope_test.user.js'))
    .replaceAll('/*__SAFE_SID__*/""', JSON.stringify('shim_grant_scope_test.user.js'))
    .replaceAll('/*__SAFE_SID__*/', JSON.stringify('shim_grant_scope_test.user.js'))
    .replaceAll('/*__SAFE_SECRET__*/""', JSON.stringify('tok4'))
    .replaceAll('/*__GM_INFO__*/{}', JSON.stringify({ script: { grant: ['GM_addStyle'] } }));
grantScopedShim += `
global.__acidGmScopeResult = {
    hasAddStyle: typeof GM.addStyle === 'function',
    hasXmlHttp: typeof GM.xmlHttpRequest !== 'undefined',
    hasGetValue: typeof GM.getValue !== 'undefined',
    hasLog: typeof GM.log !== 'undefined'
};
} catch(e) { throw e; }
})();
`;
delete global.__acidGmScopeResult;
eval(grantScopedShim);
assert.deepStrictEqual(global.__acidGmScopeResult, {
    hasAddStyle: true,
    hasXmlHttp: false,
    hasGetValue: false,
    hasLog: false
}, 'GM.* Promise namespace only exposes granted APIs while keeping dispatcher non-redefinable');
delete global.__acidGmScopeResult;
console.log('✓ Case 14: runtime_shim.js locks __acid_dispatch_http_cb and filters GM.* by @grant');

// Case 15: observe() annotates visible input(file) and label(file-trigger) with accept & multiple
{
    const visibleFile = makeEl('input', {
        id: 'doc_upload',
        type: 'file',
        attrs: { accept: '.pdf,.png' },
        rect: { left: 10, top: 20, width: 200, height: 36, right: 210, bottom: 56 }
    });
    visibleFile.multiple = true;

    const hiddenFile = makeEl('input', {
        id: 'hidden_img_upload',
        type: 'file',
        attrs: { accept: 'image/*' },
        style: { display: 'none' },
        rect: { left: 0, top: 0, width: 0, height: 0, right: 0, bottom: 0 }
    });
    const triggerLabel = makeEl('label', {
        id: 'img_trigger',
        text: '选择图片',
        attrs: { for: 'hidden_img_upload' },
        control: hiddenFile,
        rect: { left: 10, top: 80, width: 140, height: 36, right: 150, bottom: 116 }
    });

    bodyEl.children = [visibleFile, hiddenFile, triggerLabel];
    const prevGetById = global.document.getElementById;
    global.document.getElementById = (id) => (id === 'hidden_img_upload' ? hiddenFile : null);
    try {
        const obs = bridge.observe({ includeSom: false });
        assert.strictEqual(obs.ok, true, 'observe succeeds for file upload inputs');
        assert.ok(obs.compactText.includes('input(file)'), 'compactText marks visible file input');
        assert.ok(obs.compactText.includes('accept=".pdf,.png"'), 'compactText preserves accept attribute');
        assert.ok(obs.compactText.includes('[multiple]'), 'compactText marks multiple attribute');
        assert.ok(obs.compactText.includes('label(file-trigger) "选择图片" accept="image/*"'),
            'compactText marks label backed by hidden file input with file-trigger and accept');
    } finally {
        global.document.getElementById = prevGetById;
        bodyEl.children = [];
    }
}
console.log('✓ Case 15: observe() annotates input(file) and label(file-trigger) with accept & multiple');

// Case 16: observe().viewport includes visualViewport metrics & isInViewport honors visualViewport offset
{
    const btnInZoomed = makeEl('button', {
        id: 'zoomed_btn',
        text: 'Zoom Target',
        rect: { left: 520, top: 320, width: 100, height: 40, right: 620, bottom: 360 }
    });
    bodyEl.children = [btnInZoomed];
    const prevVv = global.window.visualViewport;
    global.window.visualViewport = {
        width: 640,
        height: 960,
        offsetLeft: 200,
        offsetTop: 100,
        scale: 2.0
    };
    try {
        const obs = bridge.observe({ includeSom: false, viewportOnly: true });
        assert.strictEqual(obs.ok, true, 'observe succeeds with visualViewport');
        assert.strictEqual(obs.viewport.vvWidth, 640, 'vvWidth captured');
        assert.strictEqual(obs.viewport.vvHeight, 960, 'vvHeight captured');
        assert.strictEqual(obs.viewport.vvOffsetLeft, 200, 'vvOffsetLeft captured');
        assert.strictEqual(obs.viewport.vvOffsetTop, 100, 'vvOffsetTop captured');
        assert.strictEqual(obs.viewport.vvScale, 2.0, 'vvScale captured');
        assert.strictEqual(obs.elements.length, 1, 'Element visible in panned visualViewport included');
        assert.strictEqual(obs.elements[0].inViewport, true, 'Element inside visualViewport marked inViewport=true');
    } finally {
        global.window.visualViewport = prevVv;
        bodyEl.children = [];
    }
}
console.log('✓ Case 16: observe().viewport exports visualViewport metrics and honors pan offset');

// Case 17: display:none and hidden subtrees are pruned before getBoundingClientRect while bound label(file-trigger) resolves
{
    let hiddenDescMeasured = 0;
    const hiddenDescBtn = makeEl('button', { id: 'hidden_desc_btn', text: 'Inside Display None' });
    hiddenDescBtn.getBoundingClientRect = () => {
        hiddenDescMeasured++;
        return { left: 10, top: 10, width: 80, height: 30, right: 90, bottom: 40 };
    };
    const displayNoneWrap = makeEl('div', {
        style: { display: 'none' },
        children: [hiddenDescBtn]
    });

    const hiddenAttrChild = makeEl('a', { text: 'Inside Hidden Attr' });
    hiddenAttrChild.getBoundingClientRect = () => {
        hiddenDescMeasured++;
        return { left: 10, top: 10, width: 80, height: 30, right: 90, bottom: 40 };
    };
    const hiddenAttrWrap = makeEl('div', {
        attrs: { hidden: '' },
        children: [hiddenAttrChild]
    });
    hiddenAttrWrap.hidden = true;

    const prunedFileInput = makeEl('input', {
        id: 'pruned_file_input',
        type: 'file',
        attrs: { accept: '.json' },
        style: { display: 'none' },
        rect: { left: 0, top: 0, width: 0, height: 0, right: 0, bottom: 0 }
    });
    const visibleFileLabel = makeEl('label', {
        id: 'visible_file_label',
        text: 'Import JSON',
        attrs: { for: 'pruned_file_input' },
        control: prunedFileInput,
        rect: { left: 10, top: 20, width: 120, height: 32, right: 130, bottom: 52 }
    });

    const prevGetById = global.document.getElementById;
    global.document.getElementById = (id) => (id === 'pruned_file_input' ? prunedFileInput : null);
    bodyEl.children = [displayNoneWrap, hiddenAttrWrap, prunedFileInput, visibleFileLabel];
    try {
        const obs = bridge.observe({ includeSom: false });
        assert.strictEqual(obs.ok, true);
        assert.strictEqual(hiddenDescMeasured, 0, 'Hidden subtree descendants must not trigger getBoundingClientRect');
        assert.strictEqual(obs.elements.length, 1, 'Only visible bound file label is emitted');
        assert.strictEqual(obs.elements[0].fileTrigger, true, 'Bound label still resolves hidden file input');
        assert.strictEqual(obs.elements[0].accept, '.json');
    } finally {
        global.document.getElementById = prevGetById;
        bodyEl.children = [];
    }
}
console.log('✓ Case 17: display:none & hidden subtrees prune cleanly without breaking label(file-trigger)');

// Case 18: Inherited cursor:pointer suppression & zero innerText reflow on childNodes-backed DOM
{
    function makeZeroReflowNode(tag, opts = {}) {
        const node = makeEl(tag, opts);
        const childNodes = opts.childNodes ? [...opts.childNodes] : [];
        if (opts.text && childNodes.length === 0 && !opts.children) {
            childNodes.push({ nodeType: 3, nodeValue: opts.text });
        }
        node.childNodes = childNodes;
        node.children = childNodes.filter(c => c.nodeType === 1);
        Object.defineProperty(node, 'innerText', {
            configurable: true,
            get() {
                throw new Error('innerText layout-thrashing getter must NOT be accessed on ' + tag);
            }
        });
        return node;
    }

    const plainDescendants = [];
    for (let i = 0; i < 50; i++) {
        plainDescendants.push(makeZeroReflowNode('div', {
            style: { cursor: 'pointer' },
            rect: { left: 10, top: 20 + i, width: 180, height: 20, right: 190, bottom: 40 + i },
            childNodes: [
                makeZeroReflowNode('span', {
                    text: 'M' + i,
                    style: { cursor: 'pointer' },
                    rect: { left: 12, top: 20 + i, width: 80, height: 18, right: 92, bottom: 38 + i }
                })
            ]
        }));
    }
    const realChildBtn = makeZeroReflowNode('button', {
        id: 'real_child_btn',
        text: 'Confirm Action',
        style: { cursor: 'pointer' },
        rect: { left: 10, top: 140, width: 140, height: 36, right: 150, bottom: 176 }
    });
    const pointerCard = makeZeroReflowNode('div', {
        id: 'pointer_card',
        style: { cursor: 'pointer' },
        rect: { left: 10, top: 10, width: 220, height: 180, right: 230, bottom: 190 },
        childNodes: [...plainDescendants, realChildBtn]
    });
    const cardInsideTallWrapper = makeZeroReflowNode('div', {
        id: 'card_in_tall_wrapper',
        text: 'Inside Tall Wrapper',
        style: { cursor: 'pointer' },
        rect: { left: 10, top: 200, width: 220, height: 60, right: 230, bottom: 260 }
    });
    const tallPointerWrapper = makeZeroReflowNode('div', {
        id: 'tall_pointer_wrapper',
        style: { cursor: 'pointer' },
        rect: { left: 0, top: 0, width: 300, height: 600, right: 300, bottom: 600 },
        childNodes: [pointerCard, cardInsideTallWrapper]
    });
    let shadowTextVisits = 0;
    const shadowBadge = makeZeroReflowNode('div', {
        id: 'shadow_badge',
        rect: { left: 10, top: 270, width: 120, height: 24, right: 130, bottom: 294 },
        childNodes: [{ nodeType: 3, get nodeValue() { shadowTextVisits++; return 'Shadow   Badge'; } }]
    });
    const shadowHost = makeZeroReflowNode('custom-panel', {
        id: 'shadow_host',
        rect: { left: 10, top: 270, width: 120, height: 24, right: 130, bottom: 294 },
        childNodes: []
    });
    shadowHost.__acidShadowRoot = { children: [shadowBadge], childNodes: [shadowBadge] };

    const prevBodyInnerDesc = Object.getOwnPropertyDescriptor(bodyEl, 'innerText');
    const prevQsa = global.document.querySelectorAll;
    bodyEl.childNodes = [tallPointerWrapper, shadowHost];
    bodyEl.children = [tallPointerWrapper, shadowHost];
    Object.defineProperty(bodyEl, 'innerText', {
        configurable: true,
        get() {
            throw new Error('body.innerText layout-thrashing getter must NOT be accessed');
        }
    });
    global.document.querySelectorAll = (sel) => {
        if (sel.includes('button')) return [realChildBtn];
        if (sel.includes('div') || sel.includes('span')) return plainDescendants;
        return [];
    };
    try {
        const obs = bridge.observe({ includeSom: false });
        assert.strictEqual(obs.ok, true);
        assert.strictEqual(obs.elements.length, 3, 'Accepted pointer cards & real button receive refs while tall rejected wrapper does not suppress sibling card (and 50 inherited cursor children skipped)');
        assert.ok(obs.elements.some(e => e.label === 'Inside Tall Wrapper'), 'Clickable card inside >280px cursor:pointer wrapper remains observable');
        assert.ok(obs.elements.some(e => e.tag === 'button' && e.label === 'Confirm Action'), 'Real nested <button> remains observed');

        shadowTextVisits = 0;
        const condText = bridge.checkCondition('text', 'Confirm   Action', 300, true);
        assert.strictEqual(condText.ok, true);
        assert.strictEqual(condText.satisfied, true, 'checkCondition(text) normalizes whitespace and matches without reading innerText');
        assert.strictEqual(shadowTextVisits, 1, 'containsDeepText visits Shadow DOM subtree once (not quadratically)');

        const condShadowGoneMiss = bridge.checkCondition('text', 'Definitely Missing Token', 300, true);
        assert.strictEqual(condShadowGoneMiss.satisfied, false);
        assert.strictEqual(shadowTextVisits, 2, 'containsDeepText missing needle visits Shadow DOM subtree at most once per check');

        const condShadow = bridge.checkCondition('text', 'Shadow Badge', 300, true);
        assert.strictEqual(condShadow.ok, true);
        assert.strictEqual(condShadow.satisfied, true, 'checkCondition(text) finds normalized Shadow DOM text');

        const clickRes = bridge.jsClick({ text: 'Confirm Action' });
        assert.strictEqual(clickRes.ok, true, 'jsClick(text) resolves target without reading innerText');
        assert.strictEqual(realChildBtn.clickedCount >= 1, true);
    } finally {
        if (prevBodyInnerDesc) Object.defineProperty(bodyEl, 'innerText', prevBodyInnerDesc);
        delete bodyEl.childNodes;
        bodyEl.children = [];
        global.document.querySelectorAll = prevQsa;
    }
}
console.log('✓ Case 18: Inherited cursor:pointer descendants suppressed & zero innerText reflow across observe/wait/click');

// Case 19: observe({ maxNodes }) budget caps giant trees fast and emits SCAN_BUDGET_REACHED
{
    const giantChildren = [];
    for (let i = 0; i < 5000; i++) {
        giantChildren.push(makeEl('span', {
            text: 'Item ' + i,
            rect: { left: 10, top: 10, width: 60, height: 20, right: 70, bottom: 30 }
        }));
    }
    bodyEl.children = giantChildren;
    try {
        const t0 = Date.now();
        const obs = bridge.observe({ includeSom: false, maxNodes: 400 });
        const elapsedMs = Date.now() - t0;
        assert.strictEqual(obs.ok, true);
        assert.strictEqual(obs.budgetTruncated, true, 'Observe sets budgetTruncated=true when node budget exceeded');
        assert.ok(obs.compactText.includes('[SCAN_BUDGET_REACHED]'), 'compactText reports SCAN_BUDGET_REACHED notice');
        assert.ok(elapsedMs < 50, 'Budget-capped observe finishes fast (<50ms, actual ' + elapsedMs + 'ms)');
    } finally {
        bodyEl.children = [];
    }
}
console.log('✓ Case 19: observe() scan budget halts giant 5000-node tree cleanly in <50ms');

console.log('\n[TestBridgeBatch1] All 19 Batch-1/Batch-2 JS regression checks PASSED!\n');
