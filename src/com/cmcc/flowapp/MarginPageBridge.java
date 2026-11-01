package com.cmcc.flowapp;

import org.json.JSONObject;

/** Current marginQuery page only. No business API, captcha protocol or JS bridge. */
final class MarginPageBridge {
    private MarginPageBridge() {}
    static final String ENTRY_URL = "https://wx.10086.cn/cmcc-service/app-pages/main/marginQuery";
    static final String PROBE_JS =
            "(function () {\n" +
            "  try {\n" +
            "    function list(x) { return !x ? [] : Array.isArray(x) ? x : [x]; }\n" +
            "    function visible(el) { if (!el) return false; var s = window.getComputedStyle(el); return s.display !== 'none' && s.visibility !== 'hidden' && el.getClientRects().length > 0; }\n" +
            "    function smsPopup() { var nodes = document.querySelectorAll('.popup'); for (var i = 0; i < nodes.length; i++) { if (visible(nodes[i].querySelector('input.num')) && visible(nodes[i].querySelector('input.code'))) return nodes[i]; } return null; }\n" +
            "    var root = null, nodes = [document.body].concat(Array.prototype.slice.call(document.querySelectorAll('div'), 0, 6000));\n" +
            "    for (var i = 0; i < nodes.length; i++) { var vm = nodes[i] && nodes[i].__vue__, hops = 0; while (vm && !vm.$store && vm.$parent && hops++ < 12) vm = vm.$parent; if (vm && vm.$store) { root = vm; break; } }\n" +
            "    var st = root && root.$store.state, popup = smsPopup(), button = popup && popup.querySelector('.codeBtn');\n" +
            "    var text = button ? button.textContent.trim() : '', match = text.match(/^(\\d+)s$/), smsSeconds = match ? Number(match[1]) : 0;\n" +
            "    var smsVm = popup && popup.__vue__, hops = 0; while (smsVm && smsVm.$options.name !== 'appSms' && smsVm.$parent && hops++ < 8) smsVm = smsVm.$parent;\n" +
            "    var toast = '', toasts = document.querySelectorAll('.van-toast'); for (var ti = 0; ti < toasts.length; ti++) if (visible(toasts[ti])) toast = toasts[ti].textContent.trim();\n" +
            "    var logged = !!(st && st.login && st.login.isLogin && st.userInfo && st.userInfo.phoneMask);\n" +
            "    var result = {state: 'loading', authenticated: logged, items: [], smsSeconds: smsSeconds, smsSending: !!(smsVm && smsVm.sendMsgState === 'loading'), error: toast};\n" +
            "    if (popup && !logged) { result.state = 'login'; return JSON.stringify(result); }\n" +
            "    if (!st || !logged) return JSON.stringify(result);\n" +
            "    var mod = st.planRemainQryInfo || {}, q = mod.newPlanRemainQry;\n" +
            "    if (mod.newPlanRemainQryLoadState !== 'loadCompleted') return JSON.stringify(result);\n" +
            "    if (!q || !q.planRemianFlowListRes) { result.state = 'error'; result.error = mod.newPlanRemainQryErrorText || '官方页面未返回流量余量，请刷新重试'; return JSON.stringify(result); }\n" +
            "    function amount(value, unit) {\n" +
            "      if (value == null || value === '') return '';\n" +
            "      if (typeof value === 'object') return amount(value.value, value.unit || unit);\n" +
            "      if (value === 'N') return '不限';\n" +
            "      if (value === '--') return '--';\n" +
            "      var str = String(value).trim();\n" +
            "      if (/^\\d+(?:\\.\\d+)?\\s*(?:KB|MB|GB|TB)$/i.test(str)) return str.replace(/\\s+/g, '');\n" +
            "      var n = Number(value); if (!isFinite(n) || n < 0) return '';\n" +
            "      unit = String(unit || '').toUpperCase();\n" +
            "      if (unit === '03' || unit === 'MB') return n + 'MB';\n" +
            "      if (unit === '04' || unit === 'GB') return n + 'GB';\n" +
            "      if (unit === 'KB' || unit === 'TB') return n + unit;\n" +
            "      return '';\n" +
            "    }\n" +
            "    function add(name, cat, used, remain, total, unit) {\n" +
            "      var a = amount(used, unit), b = amount(remain, unit), c = amount(total, unit);\n" +
            "      if (name && (a || b || c)) result.items.push({name: String(name), cat: cat, used: a, remain: b, total: c});\n" +
            "    }\n" +
            "    list(q.planRemianFlowListRes).forEach(function (section) {\n" +
            "      list(section.planRemianFlowRes).forEach(function (group) {\n" +
            "        var type = String(group.flowtype), cat = type === '0' ? '国内通用流量' : type === '1' ? '国内定向流量' : type === '2' ? '国内其他流量' : type === '3' ? 'WLAN流量' : '其他流量';\n" +
            "        var start = result.items.length;\n" +
            "        list(group.planRemianFlowInfoRes).forEach(function (plan) {\n" +
            "          var flows = list(plan.flowInfoRes);\n" +
            "          if (flows.length) flows.forEach(function (flow) {\n" +
            "            var name = plan.planName || flow.flowName || cat;\n" +
            "            if (flow.flowName && plan.planName && flow.flowName !== plan.planName) name += ' · ' + flow.flowName;\n" +
            "            add(name, cat, flow.usedRes, flow.remainRes, flow.totalRes, flow.unit || plan.unit || group.unit || section.unit);\n" +
            "          });\n" +
            "          else add(plan.planName || cat, cat, plan.usedRes, plan.remainRes, plan.totalRes, plan.unit || group.unit || section.unit);\n" +
            "        });\n" +
            "        if (result.items.length === start) add(cat + '（汇总）', cat, group.flowUsdNum, group.flowRemainNum, group.flowSumNum, group.unit || section.unit);\n" +
            "      });\n" +
            "    });\n" +
            "    var shared = st.userShareInfo || {}, overall = shared.shareFlowOverallData || {};\n" +
            "    function share(x, name) { if (x) add(name, '共享流量', x.overallUsedData, x.overallRemainData, x.overallTotalData, ''); }\n" +
            "    if (shared.shareUserInfoLoadState === 'loadCompleted') { share(overall.mainShareFlowOverallData, '主卡共享流量'); share(overall.subShareFlowOverallData, '副卡共享流量'); }\n" +
            "    result.partial = !!(shared.shareUserInfoLoadState && shared.shareUserInfoLoadState !== 'loadCompleted');\n" +
            "    result.state = result.items.length ? 'ready' : 'empty';\n" +
            "    if (result.state === 'empty') result.error = mod.newPlanRemainQryErrorText || '官方页面没有可展示的流量条目';\n" +
            "    return JSON.stringify(result);\n" +
            "  } catch (e) { return JSON.stringify({state: 'error', authenticated: false, items: [], smsSeconds: 0, smsSending: false, error: '新页面数据结构解析失败，请刷新重试'}); }\n" +
            "})()\n";
    private static final String SMS_JS_TEMPLATE =
            "(function(){try{function visible(el){if(!el)return false;var s=window.getComputedStyle(el);return s.display!=='none'&&s.visibility!=='hidden'&&el.getClientRects().length>0;}var pop=null,all=document.querySelectorAll('.popup');for(var i=0;i<all.length;i++)if(visible(all[i].querySelector('input.num'))&&visible(all[i].querySelector('input.code'))){pop=all[i];break;}function fail(msg){return JSON.stringify({ok:false,error:msg});}if(!pop)return fail('新页面尚未显示短信登录，请稍候再试');var p=pop.querySelector('input.num'),c=pop.querySelector('input.code'),b=pop.querySelector('.codeBtn');function input(el,value){el.value=value;el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));}var phone=__PHONE__;if(!/^1[3456789]\\d{9}$/.test(phone))return fail('请输入正确的11位移动手机号码');if(!b||b.textContent.trim()!=='获取验证码')return fail('请等待本次验证码倒计时结束后再重新获取');var v=pop.__vue__,h=0;while(v&&v.$options.name!=='appSms'&&v.$parent&&h++<8)v=v.$parent;if(v&&v.sendMsgState==='loading')return fail('验证码请求正在处理中，请勿重复操作');input(p,phone);input(c,'');b.click();return JSON.stringify({ok:true,error:''});}catch(e){return JSON.stringify({ok:false,error:'当前新页面登录控件已变化，请刷新后重试'});}})()";
    private static final String LOGIN_JS_TEMPLATE =
            "(function(){try{function visible(el){if(!el)return false;var s=window.getComputedStyle(el);return s.display!=='none'&&s.visibility!=='hidden'&&el.getClientRects().length>0;}var pop=null,all=document.querySelectorAll('.popup');for(var i=0;i<all.length;i++)if(visible(all[i].querySelector('input.num'))&&visible(all[i].querySelector('input.code'))){pop=all[i];break;}function fail(msg){return JSON.stringify({ok:false,error:msg});}if(!pop)return fail('新页面尚未显示短信登录，请稍候再试');var p=pop.querySelector('input.num'),c=pop.querySelector('input.code'),b=pop.querySelector('.codeBtn');function input(el,value){el.value=value;el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));}var phone=__PHONE__;if(!/^1[3456789]\\d{9}$/.test(phone))return fail('请输入正确的11位移动手机号码');var code=__CODE__;if(!/^\\d{6}$/.test(code))return fail('请输入6位短信验证码');if(p.value.trim()!==phone)return fail('手机号已变化，请重新获取验证码');if(!b||!/^\\d+s$/.test(b.textContent.trim()))return fail('官网60秒提交窗口已结束，请重新获取验证码');input(c,code);var login=pop.querySelector('.loginBtn, .noLogin');if(!login)return fail('未找到新页面登录按钮，请刷新重试');login.click();return JSON.stringify({ok:true,error:''});}catch(e){return JSON.stringify({ok:false,error:'当前新页面登录控件已变化，请刷新后重试'});}})()";
    static String requestSms(String phone) {
        return SMS_JS_TEMPLATE.replace("__PHONE__", JSONObject.quote(phone == null ? "" : phone));
    }
    static String submitLogin(String phone, String code) {
        return LOGIN_JS_TEMPLATE.replace("__PHONE__", JSONObject.quote(phone == null ? "" : phone))
                .replace("__CODE__", JSONObject.quote(code == null ? "" : code));
    }
}
