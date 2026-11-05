// Mock browser environment for agent_bridge.js fill verification testing
const fs = require('fs');
const assert = require('assert');

// Create mock DOM environment
global.window = global;
global.documentRevision = 1;
global.document = {
    title: 'Test Page',
    addEventListener: () => {},
    removeEventListener: () => {},
    createElement: (tag) => createMockElement(tag),
    querySelectorAll: () => [],
    querySelector: () => null,
    body: { children: [] },
    execCommand: (cmd, showUi, val) => true
};
global.location = { href: 'https://example.com' };
global.KeyboardEvent = class KeyboardEvent { constructor(t, o) { Object.assign(this, o, { type: t }); } };
global.Event = class Event { constructor(t, o) { Object.assign(this, o, { type: t }); } };
global.InputEvent = class InputEvent extends global.Event {};
global.HTMLInputElement = function HTMLInputElement() {};
global.HTMLInputElement.prototype = {
    get value() { return this._val || ''; },
    set value(v) { this._val = String(v); }
};
global.HTMLTextAreaElement = function HTMLTextAreaElement() {};
global.HTMLTextAreaElement.prototype = {
    get value() { return this._val || ''; },
    set value(v) { this._val = String(v); }
};

function createMockElement(tag, attrs = {}) {
    const el = {
        tagName: tag.toUpperCase(),
        isConnected: true,
        attributes: { ...attrs },
        getAttribute: (k) => el.attributes[k] || null,
        setAttribute: (k, v) => { el.attributes[k] = v; },
        removeAttribute: (k) => { delete el.attributes[k]; },
        dispatchEvent: (evt) => {
            if (el.onEvent) el.onEvent(evt);
            return true;
        },
        scrollIntoView: () => {},
        focus: () => { el.focused = true; },
        style: {},
        classList: { contains: () => false },
        getBoundingClientRect: () => ({ x: 10, y: 10, width: 100, height: 30, top: 10, left: 10, bottom: 40, right: 110 })
    };
    if (tag.toLowerCase() === 'input' || tag.toLowerCase() === 'textarea') {
        el._val = '';
        Object.defineProperty(el, 'value', {
            get() { return el._val; },
            set(v) { el._val = String(v); },
            configurable: true
        });
    }
    return el;
}

// Load agent_bridge.js in context
const code = fs.readFileSync('assets/agent_bridge.js', 'utf8');
eval(code);
const bridge = window.__acidBridge;

console.log('[TestFill] Testing agent_bridge.js fill & readback verification...');

let currentTarget = null;
global.document.querySelector = (sel) => currentTarget;

// 1. Target disabled element
const disabledInput = createMockElement('input');
disabledInput.disabled = true;
currentTarget = disabledInput;
const resDisabled = bridge.fill({ selector: '#input' }, 'hello', true, false);
assert.strictEqual(resDisabled.ok, false, 'Disabled input must fail');
assert.strictEqual(resDisabled.error, 'ELEMENT_DISABLED', 'Error code must be ELEMENT_DISABLED');
console.log('✓ Case 1: Disabled element rejected');

// 2. Target readonly element
const readonlyInput = createMockElement('input');
readonlyInput.readOnly = true;
currentTarget = readonlyInput;
const resReadonly = bridge.fill({ selector: '#input' }, 'hello', true, false);
assert.strictEqual(resReadonly.ok, false, 'ReadOnly input must fail');
assert.strictEqual(resReadonly.error, 'ELEMENT_READONLY', 'Error code must be ELEMENT_READONLY');
console.log('✓ Case 2: ReadOnly element rejected');

// 3. Target file input
const fileInput = createMockElement('input');
fileInput.type = 'file';
currentTarget = fileInput;
const resFile = bridge.fill({ selector: '#input' }, 'test.pdf', true, false);
assert.strictEqual(resFile.ok, false, 'File input must fail');
assert.strictEqual(resFile.error, 'FILE_INPUT_NOT_SUPPORTED');
console.log('✓ Case 3: File input rejected');

// 4. Normal text input success
const textInput = createMockElement('input');
textInput.type = 'text';
currentTarget = textInput;
const resText = bridge.fill({ selector: '#input' }, 'Acid Browser 2026', true, false);
assert.strictEqual(resText.ok, true, 'Valid fill must succeed');
assert.strictEqual(resText.verified, true, 'Verified flag must be true');
assert.strictEqual(resText.value, 'Acid Browser 2026', 'Readback value matches expected');
console.log('✓ Case 4: Normal text input success with verified: true');

// 5. Verification failure: simulated React controlled component rejecting input (reverts to empty)
const controlledInput = createMockElement('input');
controlledInput.type = 'text';
controlledInput.onEvent = (evt) => {
    if (evt.type === 'input') {
        // Framework rejects and resets to empty
        controlledInput._val = '';
    }
};
currentTarget = controlledInput;
const resControlledFail = bridge.fill({ selector: '#input' }, 'rejected_text', true, false);
assert.strictEqual(resControlledFail.ok, false, 'Verification failure must NOT report ok: true');
assert.strictEqual(resControlledFail.error, 'VERIFICATION_FAILED', 'Error code must be VERIFICATION_FAILED');
console.log('✓ Case 5: Controlled component rejection caught by readback verification');

// 6. Sensitive input (password) masking and verification
const passInput = createMockElement('input');
passInput.type = 'password';
currentTarget = passInput;
const resPass = bridge.fill({ selector: '#input' }, 'secretPassword123', true, false);
assert.strictEqual(resPass.ok, true, 'Password fill must succeed');
assert.strictEqual(resPass.verified, true, 'Password verified');
assert.strictEqual(resPass.value, '***', 'Password value must be masked in receipt');
console.log('✓ Case 6: Password fill verified and masked');

// 7. ContentEditable element success
const editableDiv = createMockElement('div');
editableDiv.isContentEditable = true;
editableDiv.innerText = '';
currentTarget = editableDiv;
const resEditable = bridge.fill({ selector: '#input' }, 'Email content body', true, false);
assert.strictEqual(resEditable.ok, true, 'ContentEditable fill must succeed');
assert.strictEqual(resEditable.verified, true, 'ContentEditable verified');
assert.strictEqual(resEditable.value, 'Email content body');
console.log('✓ Case 7: ContentEditable element fill verified');

console.log('\n[TestFill] All 7 JS bridge fill readback verification tests PASSED!\n');
