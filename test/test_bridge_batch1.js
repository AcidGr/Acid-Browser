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

console.log('\n[TestBridgeBatch1] All 10 Batch-1/Batch-2 JS regression checks PASSED!\n');
