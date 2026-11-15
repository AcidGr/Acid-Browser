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

console.log('\n[TestBridgeBatch1] All 4 Batch-1 JS regression checks PASSED!\n');
