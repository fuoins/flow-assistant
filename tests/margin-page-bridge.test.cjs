'use strict';

// Dependency-free tests for the actual Java-embedded page scripts.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const javaPath = path.resolve(__dirname, '../src/com/cmcc/flowapp/MarginPageBridge.java');
const java = fs.readFileSync(javaPath, 'utf8');
const activityPath = path.resolve(__dirname, '../src/com/cmcc/flowapp/MainActivity.java');
const activity = fs.readFileSync(activityPath, 'utf8');

function extractJavaString(field) {
  const re = new RegExp('(?:static\\s+)?(?:final\\s+)?String\\s+' + field + '\\s*=');
  const match = re.exec(java);
  assert.ok(match, `Could not find Java string ${field}`);
  let end = match.index + match[0].length;
  let quoted = false;
  let escaped = false;
  for (; end < java.length; end++) {
    const ch = java[end];
    if (quoted) {
      if (escaped) escaped = false;
      else if (ch === '\\') escaped = true;
      else if (ch === '"') quoted = false;
    } else if (ch === '"') quoted = true;
    else if (ch === ';') break;
  }
  assert.ok(end < java.length, `Could not find end of Java string ${field}`);
  const expression = java.slice(match.index + match[0].length, end);
  const pieces = [...expression.matchAll(/"((?:\\.|[^"\\])*)"/g)];
  assert.ok(pieces.length, `No Java string literals found for ${field}`);
  return pieces.map((m) => JSON.parse('"' + m[1] + '"')).join('');
}

const scripts = {
  probe: extractJavaString('PROBE_JS'),
  smsTemplate: extractJavaString('SMS_JS_TEMPLATE'),
  loginTemplate: extractJavaString('LOGIN_JS_TEMPLATE'),
};

let cases = 0;
function test(name, fn) {
  try {
    fn();
    cases++;
    console.log(`ok ${cases} - ${name}`);
  } catch (err) {
    console.error(`not ok - ${name}`);
    throw err;
  }
}

test('startup probes the existing session before showing the SMS login form', () => {
  const start = activity.indexOf('protected void onCreate');
  const end = activity.indexOf('public void onBackPressed', start);
  assert.ok(start >= 0 && end > start, 'Could not locate MainActivity startup');
  const onCreate = activity.slice(start, end);
  assert.doesNotMatch(onCreate, /showLoginScreen\s*\(/);
  assert.match(onCreate, /showNative\s*\(\)/);
  assert.match(onCreate, /loadEntry\("正在检查登录状态…",\s*true\)/);
});

class FakeEvent {
  constructor(type, init) {
    this.type = type;
    this.bubbles = !!(init && init.bubbles);
  }
}

class FakeElement {
  constructor(tag, className = '', textContent = '') {
    this.tagName = tag.toUpperCase();
    this.className = className;
    this.textContent = textContent;
    this.value = '';
    this.children = [];
    this.parentNode = null;
    this.__vue__ = null;
    this.style = {};
    this.visible = true;
    this.clickCount = 0;
    this.events = [];
    this.listeners = Object.create(null);
  }
  append(child) {
    child.parentNode = this;
    this.children.push(child);
    return child;
  }
  getClientRects() { return this.visible ? [{}] : []; }
  addEventListener(type, fn) {
    (this.listeners[type] ||= []).push(fn);
  }
  dispatchEvent(event) {
    this.events.push(event.type);
    for (const fn of this.listeners[event.type] || []) fn.call(this, event);
    return true;
  }
  click() { this.clickCount++; }
  matches(selector) {
    return selector.split(',').some((part) => {
      const s = part.trim();
      if (s.startsWith('.')) return s.slice(1).split('.').every((c) => this.className.split(/\s+/).includes(c));
      const m = s.match(/^([a-zA-Z][\w-]*)(?:\.([\w-]+))?$/);
      return !!m && this.tagName.toLowerCase() === m[1].toLowerCase() && (!m[2] || this.className.split(/\s+/).includes(m[2]));
    });
  }
  querySelectorAll(selector) {
    const out = [];
    const visit = (node) => {
      for (const child of node.children) {
        if (child.matches(selector)) out.push(child);
        visit(child);
      }
    };
    visit(this);
    return out;
  }
  querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
}

function makeDom({ store, storeOn = 'body', popup = null, toast = null } = {}) {
  const body = new FakeElement('body');
  const divs = [];
  if (store) {
    if (storeOn === 'body') body.__vue__ = { $store: store, $parent: null };
    else {
      const div = body.append(new FakeElement('div'));
      divs.push(div);
      // Exercise the root walk: the DOM Vue instance itself has no store.
      div.__vue__ = { $options: { name: 'child' }, $parent: { $store: store, $parent: null } };
    }
  }
  if (popup) {
    const node = body.append(popup);
    if (popup.tagName === 'DIV' && !divs.includes(node)) divs.push(node);
  }
  if (toast) body.append(toast);
  const allDivs = () => {
    const found = [];
    const walk = (node) => node.children.forEach((child) => {
      if (child.tagName === 'DIV') found.push(child);
      walk(child);
    });
    walk(body);
    return found;
  };
  const document = {
    body,
    querySelectorAll(selector) {
      if (selector === 'div') return allDivs();
      return body.querySelectorAll(selector);
    },
  };
  const context = vm.createContext({
    document,
    window: { getComputedStyle: (el) => ({ display: el.style.display || 'block', visibility: el.style.visibility || 'visible' }) },
    Event: FakeEvent,
    console,
  });
  return { body, document, context, divs };
}

function run(script, dom) {
  return JSON.parse(vm.runInContext(script, dom.context, { timeout: 1000 }));
}
function baseStore(overrides = {}) {
  return {
    state: {
      login: { isLogin: true },
      userInfo: { phoneMask: 'synthetic-mask' },
      planRemainQryInfo: {
        newPlanRemainQryLoadState: 'loadCompleted',
        newPlanRemainQry: { planRemianFlowListRes: [] },
      },
      ...overrides,
    },
  };
}
function popupFixture({ buttonText = '获取验证码', sendMsgState = 'idle', visible = true } = {}) {
  const pop = new FakeElement('div', 'popup');
  const phone = pop.append(new FakeElement('input', 'num'));
  const code = pop.append(new FakeElement('input', 'code'));
  const button = pop.append(new FakeElement('button', 'codeBtn', buttonText));
  const smsVm = { $options: { name: 'appSms' }, sendMsgState, $parent: null };
  pop.__vue__ = smsVm;
  phone.visible = code.visible = visible;
  return { pop, phone, code, button, smsVm };
}
function loadedStore(list, more = {}) {
  const store = baseStore();
  Object.assign(store.state.planRemainQryInfo, {
    newPlanRemainQryLoadState: 'loadCompleted',
    newPlanRemainQry: { planRemianFlowListRes: list },
  });
  Object.assign(store.state, more);
  return store;
}

// No #app is created in these fixtures; the probe must locate Vue/store from body or div.
test('finds store on body even when #app is absent', () => {
  const result = run(scripts.probe, makeDom({ store: loadedStore([]), storeOn: 'body' }));
  assert.equal(result.state, 'empty');
  assert.equal(result.authenticated, true);
});
test('finds store through a div.__vue__ parent chain without #app', () => {
  const result = run(scripts.probe, makeDom({ store: loadedStore([]), storeOn: 'div' }));
  assert.equal(result.state, 'empty');
});
test('visible login popup takes precedence and is unauthenticated', () => {
  const store = baseStore({ login: { isLogin: false }, userInfo: {} });
  const result = run(scripts.probe, makeDom({ store, popup: popupFixture().pop }));
  assert.equal(result.state, 'login');
  assert.equal(result.authenticated, false);
});
test('Cookie presence alone does not authenticate', () => {
  const dom = makeDom({ store: baseStore({ login: { isLogin: false }, userInfo: {} }) });
  dom.context.document.cookie = 'synthetic=present';
  const result = run(scripts.probe, dom);
  assert.equal(result.authenticated, false);
  assert.equal(result.state, 'loading');
});
test('nonempty st-like data alone does not authenticate', () => {
  const result = run(scripts.probe, makeDom({ store: baseStore({ login: {}, userInfo: { st: 'synthetic-token' } }) }));
  assert.equal(result.authenticated, false);
  assert.equal(result.state, 'loading');
});
test('authenticated state remains loading while the main module loads', () => {
  const store = loadedStore([]);
  store.state.planRemainQryInfo.newPlanRemainQryLoadState = 'loading';
  assert.equal(run(scripts.probe, makeDom({ store })).state, 'loading');
});
test('loadCompleted without q is an error', () => {
  const store = baseStore();
  store.state.planRemainQryInfo.newPlanRemainQry = null;
  const result = run(scripts.probe, makeDom({ store }));
  assert.equal(result.state, 'error');
  assert.ok(result.error);
});
test('extracts object-shaped section/group/plan/flow leaf, not parent summaries', () => {
  const list = { planRemianFlowRes: { flowtype: '0', flowUsdNum: 900, flowRemainNum: 800, flowSumNum: 1700,
    planRemianFlowInfoRes: { planName: '合成套餐甲', usedRes: 500, remainRes: 500, totalRes: 1000, unit: '03',
      flowInfoRes: { flowName: '合成流量叶子', usedRes: 1, remainRes: 2, totalRes: 3, unit: '03' } } } };
  const result = run(scripts.probe, makeDom({ store: loadedStore(list) }));
  assert.equal(result.state, 'ready');
  assert.equal(result.items.length, 1);
  assert.equal(result.items[0].name, '合成套餐甲 · 合成流量叶子');
  assert.equal(result.items[0].used, '1MB');
  assert.notEqual(result.items[0].used, '500MB');
});
test('extracts array-shaped planRemianFlowListRes and nested flowInfoRes leaves', () => {
  const list = [{ planRemianFlowRes: [{ flowtype: '1', planRemianFlowInfoRes: [{ planName: '合成套餐乙',
    flowInfoRes: [{ flowName: '合成定向叶子', usedRes: { value: 2, unit: '04' }, remainRes: { value: 3, unit: '04' }, totalRes: { value: 5, unit: '04' } }] }] }] }];
  const result = run(scripts.probe, makeDom({ store: loadedStore(list) }));
  assert.equal(result.items.length, 1);
  assert.equal(result.items[0].used, '2GB');
  assert.equal(result.items[0].remain, '3GB');
});
test('keeps same-named leaves from distinct plans rather than deduplicating', () => {
  const group = { flowtype: '0', planRemianFlowInfoRes: [
    { planName: '重名合成计划', flowInfoRes: [{ flowName: '相同叶子', usedRes: 1, unit: '03' }] },
    { planName: '重名合成计划', flowInfoRes: [{ flowName: '相同叶子', usedRes: 2, unit: '03' }] },
  ] };
  const result = run(scripts.probe, makeDom({ store: loadedStore([{ planRemianFlowRes: group }]) }));
  assert.equal(result.items.length, 2);
  assert.deepEqual(result.items.map((x) => x.used), ['1MB', '2MB']);
});
test('recognizes unit codes 03=MB and 04=GB, rejects minutes/SMS/bare numbers as traffic', () => {
  const flows = [
    { flowName: '合成MB', usedRes: 7, unit: '03' },
    { flowName: '合成GB', usedRes: 8, unit: '04' },
    { flowName: '合成分钟', usedRes: 1, unit: '01' },
    { flowName: '合成短信', usedRes: 2, unit: '02' },
    { flowName: '无单位数字', usedRes: 1 },
  ];
  const result = run(scripts.probe, makeDom({ store: loadedStore([{ planRemianFlowRes: { flowtype: '0', planRemianFlowInfoRes: [{ flowInfoRes: flows }] } }]) }));
  assert.equal(result.items.length, 2);
  assert.deepEqual(result.items.map((x) => x.used), ['7MB', '8GB']);
});
test('does not drop zero-valued amounts', () => {
  const result = run(scripts.probe, makeDom({ store: loadedStore([{ planRemianFlowRes: { flowtype: '0', planRemianFlowInfoRes: [{
    planName: '合成零值计划', flowInfoRes: [{ flowName: '零值流量', usedRes: 0, remainRes: 0, totalRes: 0, unit: '03' }],
  }] } }]) }));
  assert.equal(result.state, 'ready');
  assert.equal(result.items.length, 1);
  assert.deepEqual([result.items[0].used, result.items[0].remain, result.items[0].total], ['0MB', '0MB', '0MB']);
});
test('uses group total fallback when there are no usable leaves', () => {
  const result = run(scripts.probe, makeDom({ store: loadedStore([{ planRemianFlowRes: { flowtype: '0', flowUsdNum: 1,
    flowRemainNum: 2, flowSumNum: 3, unit: '04', planRemianFlowInfoRes: [] } }]) }));
  assert.equal(result.items.length, 1);
  assert.equal(result.items[0].name, '国内通用流量（汇总）');
  assert.deepEqual([result.items[0].used, result.items[0].remain, result.items[0].total], ['1GB', '2GB', '3GB']);
});
test('a valid empty list produces empty rather than error', () => {
  const result = run(scripts.probe, makeDom({ store: loadedStore([]) }));
  assert.equal(result.state, 'empty');
  assert.equal(result.items.length, 0);
});
test('reads shared overall value/unit objects independently and marks partial without blocking domestic rows', () => {
  const store = loadedStore([{ planRemianFlowRes: { flowtype: '0', planRemianFlowInfoRes: [{
    planName: '合成国内计划', flowInfoRes: [{ flowName: '国内叶子', usedRes: 1, remainRes: 2, totalRes: 3, unit: '03' }],
  }] } }], { userShareInfo: {
    shareUserInfoLoadState: 'loading',
    shareFlowOverallData: { mainShareFlowOverallData: {
      overallUsedData: { value: 4, unit: 'MB' }, overallRemainData: { value: 5, unit: 'GB' }, overallTotalData: { value: 6, unit: '03' },
    } },
  } });
  const result = run(scripts.probe, makeDom({ store }));
  assert.equal(result.state, 'ready');
  assert.equal(result.partial, true);
  assert.equal(result.items.length, 1); // pending shared endpoint does not delay domestic data
  assert.equal(result.items[0].name, '合成国内计划 · 国内叶子');
  const shareOnly = loadedStore([], { userShareInfo: {
    shareUserInfoLoadState: 'loadCompleted',
    shareFlowOverallData: { mainShareFlowOverallData: {
      overallUsedData: { value: 4, unit: 'MB' }, overallRemainData: { value: 5, unit: 'GB' }, overallTotalData: { value: 6, unit: '03' },
    } },
  } });
  const shared = run(scripts.probe, makeDom({ store: shareOnly }));
  assert.deepEqual([shared.items[0].used, shared.items[0].remain, shared.items[0].total], ['4MB', '5GB', '6MB']);
});

const syntheticPhone = '13800000000';
const syntheticLoginPhone = '13900000000';
const syntheticCode = '123456';
function instantiateTemplate(template, replacements) {
  let script = template;
  for (const [placeholder, value] of Object.entries(replacements)) script = script.replace(placeholder, JSON.stringify(value));
  return script;
}
function assertNoDirectNetwork(script) {
  assert.doesNotMatch(script, /\b(?:fetch|XMLHttpRequest|axios)\b|\.open\s*\(|\.send\s*\(/i);
}

test('requestSms uses safe JSON phone substitution, dispatches input/change and clicks once', () => {
  const script = instantiateTemplate(scripts.smsTemplate, { '__PHONE__': syntheticPhone });
  assertNoDirectNetwork(script);
  const fields = popupFixture();
  fields.code.value = 'stale synthetic code';
  const result = run(script, makeDom({ popup: fields.pop }));
  assert.equal(result.ok, true);
  assert.equal(fields.phone.value, syntheticPhone);
  assert.equal(fields.code.value, '');
  assert.equal(fields.button.clickCount, 1);
  assert.deepEqual(fields.phone.events, ['input', 'change']);
  assert.deepEqual(fields.code.events, ['input', 'change']);
});
test('requestSms refuses a second request during countdown', () => {
  const script = instantiateTemplate(scripts.smsTemplate, { '__PHONE__': syntheticPhone });
  const fields = popupFixture({ buttonText: '59s' });
  assert.equal(run(script, makeDom({ popup: fields.pop })).ok, false);
  assert.equal(fields.button.clickCount, 0);
});
test('requestSms refuses duplicate request while Vue reports sending', () => {
  const script = instantiateTemplate(scripts.smsTemplate, { '__PHONE__': syntheticPhone });
  const fields = popupFixture({ sendMsgState: 'loading' });
  assert.equal(run(script, makeDom({ popup: fields.pop })).ok, false);
  assert.equal(fields.button.clickCount, 0);
});
test('submitLogin safely substitutes synthetic phone/code and performs only DOM input plus one click', () => {
  const script = instantiateTemplate(scripts.loginTemplate, { '__PHONE__': syntheticLoginPhone, '__CODE__': syntheticCode });
  assertNoDirectNetwork(script);
  const fields = popupFixture({ buttonText: '42s' });
  const login = fields.pop.append(new FakeElement('button', 'loginBtn'));
  fields.phone.value = syntheticLoginPhone;
  const result = run(script, makeDom({ popup: fields.pop }));
  assert.equal(result.ok, true);
  assert.equal(fields.code.value, syntheticCode);
  assert.deepEqual(fields.code.events, ['input', 'change']);
  assert.equal(login.clickCount, 1);
});
test('submitLogin rejects a changed phone and does not click', () => {
  const script = instantiateTemplate(scripts.loginTemplate, { '__PHONE__': syntheticLoginPhone, '__CODE__': syntheticCode });
  const fields = popupFixture({ buttonText: '42s' });
  fields.phone.value = '13700000000';
  const login = fields.pop.append(new FakeElement('button', 'loginBtn'));
  assert.equal(run(script, makeDom({ popup: fields.pop })).ok, false);
  assert.equal(login.clickCount, 0);
});
test('submitLogin rejects expired countdown and does not click', () => {
  const script = instantiateTemplate(scripts.loginTemplate, { '__PHONE__': syntheticLoginPhone, '__CODE__': syntheticCode });
  const fields = popupFixture({ buttonText: '获取验证码' });
  fields.phone.value = syntheticLoginPhone;
  const login = fields.pop.append(new FakeElement('button', 'loginBtn'));
  assert.equal(run(script, makeDom({ popup: fields.pop })).ok, false);
  assert.equal(login.clickCount, 0);
});
test('submitLogin rejects malformed code without submitting', () => {
  const script = instantiateTemplate(scripts.loginTemplate, { '__PHONE__': syntheticLoginPhone, '__CODE__': '12345' });
  const fields = popupFixture({ buttonText: '42s' });
  fields.phone.value = syntheticLoginPhone;
  const login = fields.pop.append(new FakeElement('button', 'loginBtn'));
  assert.equal(run(script, makeDom({ popup: fields.pop })).ok, false);
  assert.equal(login.clickCount, 0);
});

console.log(`\n${cases} test cases passed`);
