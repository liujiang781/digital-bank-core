#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""实验四 S5 页面级测试脚本（Edge + 远程调试协议，纯标准库 + websocket-client）。

覆盖：登录成功/失败、未登录跳转、存款成功、金额错误提示、连续提交防重复。
只记录实际页面结果；令牌不写入证据。

用法：
  python s5_page_test.py --edge "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe" \
      --url http://127.0.0.1:5173 --out <证据目录> --user UID-000001 --password demo123
"""

import argparse
import base64
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.request

import websocket  # pip install websocket-client


class CDP:
    def __init__(self, ws_url):
        self.ws = websocket.create_connection(ws_url, timeout=20)
        self.seq = 0

    def send(self, method, params=None):
        self.seq += 1
        mid = self.seq
        self.ws.send(json.dumps({"id": mid, "method": method, "params": params or {}}))
        while True:
            msg = json.loads(self.ws.recv())
            if msg.get("id") == mid:
                return msg

    def js(self, expression, await_promise=False):
        res = self.send("Runtime.evaluate", {
            "expression": expression,
            "returnByValue": True,
            "awaitPromise": await_promise,
        })
        result = res.get("result", {}).get("result", {})
        if "exceptionDetails" in res.get("result", {}):
            return {"_error": str(res["result"]["exceptionDetails"])}
        return result.get("value")

    def navigate(self, url):
        self.send("Page.navigate", {"url": url})
        time.sleep(2.5)

    def screenshot(self, path):
        res = self.send("Page.captureScreenshot", {"format": "png"})
        data = res.get("result", {}).get("data")
        if data:
            with open(path, "wb") as f:
                f.write(base64.b64decode(data))
            return True
        return False

    def close(self):
        try:
            self.ws.close()
        except Exception:
            pass


def wait_for(cdp, expression, timeout=12, interval=0.4):
    end = time.time() + timeout
    while time.time() < end:
        value = cdp.js(expression)
        if value:
            return value
        time.sleep(interval)
    return None


def set_input(cdp, selector, value):
    expr = """(() => {
      const el = document.querySelector(%s);
      if (!el) return false;
      const setter = Object.getOwnPropertyDescriptor(el.constructor.prototype, 'value').set;
      setter.call(el, %s);
      el.dispatchEvent(new Event('input', {bubbles: true}));
      el.dispatchEvent(new Event('change', {bubbles: true}));
      return true;
    })()""" % (json.dumps(selector), json.dumps(value))
    return cdp.js(expr)


def click(cdp, selector):
    return cdp.js("(() => { const el = document.querySelector(%s); if (!el) return false; el.click(); return true; })()" % json.dumps(selector))


def visible_text(cdp, selector="body"):
    return cdp.js("(() => { const el = document.querySelector(%s); return el ? el.innerText : ''; })()" % json.dumps(selector))


def select_account(cdp, account_id):
    """操作 Element Plus 下拉框选择账户。"""
    opened = cdp.js("(() => { const el = document.querySelector('.el-select'); if (!el) return false; el.click(); return true; })()")
    time.sleep(0.5)
    picked = cdp.js("""(() => {
      const opts = Array.from(document.querySelectorAll('.el-select-dropdown__item'))
        .filter(o => o.textContent.includes(%s));
      if (!opts.length) return false;
      opts[0].click();
      return true;
    })()""" % json.dumps(account_id))
    time.sleep(0.3)
    return bool(opened and picked)


def click_button(cdp, text):
    """点击文字匹配的按钮；避免误点布局中的“退出登录”。"""
    expr = """(() => {
      const el = Array.from(document.querySelectorAll('button')).find(b => b.innerText.trim() === %s);
      if (!el) return false;
      el.click();
      return true;
    })()""" % json.dumps(text)
    return cdp.js(expr)


def click_menu(cdp, text):
    """点击 Element Plus 菜单项；用 SPA 跳转，避免整页刷新丢失会话。"""
    expr = """(() => {
      const nodes = Array.from(document.querySelectorAll('li,a,button,span'));
      const el = nodes.find(n => n.children.length === 0 && n.textContent.trim() === %s);
      if (!el) return false;
      el.click();
      return true;
    })()""" % json.dumps(text)
    return cdp.js(expr)


def record(out, case_id, name, expect, actual, ok, note=""):
    item = {
        "编号": case_id, "功能名称": name, "期望输出": expect,
        "实际输出": actual, "发现问题": note, "结果": "通过" if ok else "失败",
        "时间": time.strftime("%Y-%m-%dT%H:%M:%S+08:00", time.localtime()), "测试人": "S5",
    }
    out.append(item)
    print(f"[{case_id}] {name}: {'通过' if ok else '失败'}")
    return item


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--edge", required=True)
    ap.add_argument("--url", default="http://127.0.0.1:5173")
    ap.add_argument("--out", required=True)
    ap.add_argument("--user", default="UID-000001")
    ap.add_argument("--password", default="demo123")
    args = ap.parse_args()

    os.makedirs(args.out, exist_ok=True)
    profile = tempfile.mkdtemp(prefix="s5-edge-")
    edge = subprocess.Popen([
        args.edge, "--headless=new", "--disable-gpu", "--no-sandbox",
        "--remote-debugging-port=9333", "--window-size=1440,900",
        "--user-data-dir=" + profile, "about:blank",
    ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    results = []
    try:
        target = None
        for _ in range(40):
            try:
                pages = json.load(urllib.request.urlopen("http://127.0.0.1:9333/json", timeout=2))
                target = next((p for p in pages if p.get("type") == "page"), None)
                if target:
                    break
            except Exception:
                pass
            time.sleep(0.3)
        if not target:
            print("无法连接 Edge 调试端口", file=sys.stderr)
            return 2
        cdp = CDP(target["webSocketDebuggerUrl"])
        cdp.send("Page.enable")
        cdp.send("Runtime.enable")

        # 页面 1：未登录访问受保护页面应跳转登录
        cdp.navigate(args.url + "/accounts")
        time.sleep(1.5)
        url1 = cdp.js("location.pathname")
        record(results, "UI-S5-001", "未登录访问账户页跳转登录", "/login",
               {"pathname": url1}, url1 == "/login")

        # 页面 2：错误密码的错误提示
        cdp.navigate(args.url + "/login")
        set_input(cdp, "input[placeholder*='身份标识']", args.user)
        set_input(cdp, "input[type='password']", "wrong-password")
        click(cdp, "button")
        time.sleep(2)
        text2 = visible_text(cdp)
        has_error = ("错误" in text2 or "失败" in text2 or "不正确" in text2)
        record(results, "UI-S5-002", "错误密码显示后端错误提示", "页面出现错误提示",
               {"页面文本片段": text2[-500:]}, has_error,
               note="提示文字取自后端 message，脚本只检查是否出现错误类关键词。")

        # 页面 3：登录成功
        cdp.navigate(args.url + "/login")
        set_input(cdp, "input[placeholder*='身份标识']", args.user)
        set_input(cdp, "input[type='password']", args.password)
        click(cdp, "button")
        landed = wait_for(cdp, "location.pathname !== '/login' && location.pathname !== '/' ? location.pathname : null")
        text3 = visible_text(cdp)
        record(results, "UI-S5-003", "登录成功后进入主界面", "离开登录页并显示账户/交易导航",
               {"pathname": cdp.js("location.pathname"), "页面文本片段": text3[:400]},
               bool(landed), note="登录态保存在 sessionStorage。")
        cdp.screenshot(os.path.join(args.out, "ui-after-login.png"))

        # 页面 4：存款成功并展示结果面板（用菜单做 SPA 跳转，保留会话）
        click_menu(cdp, "存款")
        time.sleep(1.2)
        inputs = cdp.js("Array.from(document.querySelectorAll('input')).map(e => ({type:e.type, placeholder:e.placeholder}))")
        amount_sel = "input[placeholder*='100.00']"
        if not cdp.js("!!document.querySelector(" + json.dumps(amount_sel) + ")"):
            amount_sel = "input[type='number']" if any(i['type'] == 'number' for i in inputs or []) else "input"
        selected = select_account(cdp, "ACC-0001")
        set_input(cdp, amount_sel, "1.00")
        click_button(cdp, "提交存款")
        time.sleep(0.6)
        confirmed = click_button(cdp, "确认存入")
        time.sleep(2.5)
        text4 = visible_text(cdp)
        ok4 = ("成功" in text4 or "已成功" in text4)
        record(results, "UI-S5-004", "页面存款成功并显示结果", "结果面板出现成功状态",
               {"输入框列表": inputs, "账户已选择": selected, "二次确认已点击": confirmed,
                  "页面文本片段": text4[-600:]}, ok4,
               note="页面有二次确认弹窗，脚本点击“确认存入”完成提交。")
        cdp.screenshot(os.path.join(args.out, "ui-deposit-result.png"))

        # 页面 5：金额非法的前端校验
        click_menu(cdp, "存款")
        time.sleep(1.2)
        set_input(cdp, amount_sel, "-5")
        click_button(cdp, "提交存款")
        time.sleep(1)
        text5 = visible_text(cdp)
        ok5 = ("金额" in text5 and ("必须" in text5 or "大于" in text5 or "正确" in text5 or "有效" in text5 or "不能为负" in text5 or "两位小数" in text5))
        record(results, "UI-S5-005", "非法金额前端校验提示", "页面提示金额格式错误",
               {"页面文本片段": text5[-500:]}, ok5,
               note="前端校验不能替代后端校验；此项只验证页面提示是否出现。")

        # 页面 6：连续点击两次提交（防重复提交）
        click_menu(cdp, "存款")
        time.sleep(1.2)
        select_account(cdp, "ACC-0001")
        set_input(cdp, amount_sel, "2.00")
        cdp.js("""(() => { const bs = Array.from(document.querySelectorAll('button')).filter(b => b.innerText.trim() === '提交存款'); if (!bs.length) return false; bs[0].click(); bs[0].click(); return true; })()""")
        time.sleep(0.8)
        # 连点两次后，页面上只应存在一个确认弹窗
        dialog_count = cdp.js("document.querySelectorAll('.el-message-box').length")
        confirm_count = cdp.js("Array.from(document.querySelectorAll('button')).filter(b => b.innerText.trim() === '确认存入').length")
        click_button(cdp, "确认存入")
        time.sleep(2.5)
        text6 = visible_text(cdp)
        buttons = cdp.js("Array.from(document.querySelectorAll('button')).map(b => ({text:b.innerText, disabled:b.disabled}))")
        ok6 = (dialog_count == 1 and confirm_count == 1 and text6.count('交易号') == 1)
        record(results, "UI-S5-006", "连续两次提交按钮",
               "只有一个确认弹窗，点击后结果面板只显示一次交易",
               {"确认弹窗数": dialog_count, "确认按钮数": confirm_count,
                "结果面板交易号次数": text6.count('交易号'),
                "按钮状态": buttons, "页面文本片段": text6[-600:]}, ok6,
               note="防重复入账由 E2E-S5-006 与数据库核对证明；本用例验证确认弹窗不残留。")
        cdp.screenshot(os.path.join(args.out, "ui-double-submit.png"))

        cdp.close()
    finally:
        edge.terminate()
        try:
            edge.wait(timeout=8)
        except Exception:
            edge.kill()

    payload = {
        "执行时间": time.strftime("%Y-%m-%dT%H:%M:%S+08:00", time.localtime()),
        "说明": "页面用例由 Edge 无头浏览器通过远程调试协议执行；结果由页面文本与跳转自动判定，需人工复核。",
        "用例": results,
    }
    path = os.path.join(args.out, "s5-page-results.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)
    print("证据写入:", path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
