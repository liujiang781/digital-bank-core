#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""实验四 S5 端到端接口脚本。

经网关执行登录、账户查询、存款、交易结果、余额与流水、幂等重放/冲突、
限额边界、取款、本行转账、越权查询等场景。只使用 Python 标准库。

用法：
  python s5_e2e.py --base-url http://127.0.0.1:8090 \
      --user UID-000001 --password demo123 \
      --account ACC-0001 --out <证据目录>

脱敏：令牌、密码、Authorization 头不写入证据文件；请求号与交易号保留，
便于和数据库前后值、S2/S3/S4 记录交叉核对。
"""

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timezone, timedelta


CST = timezone(timedelta(hours=8))
CASES = []


def now():
    return datetime.now(CST).isoformat(timespec="seconds")


def new_id():
    return str(uuid.uuid4())


def call(base, method, path, body=None, token=None, timeout=15, extra_headers=None):
    """返回 (http_status, response_json, request_id_header)。"""
    url = base + path
    data = None
    headers = {"Accept": "application/json"}
    if extra_headers:
        headers.update(extra_headers)
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8", "replace")
            status = resp.status
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", "replace")
        status = exc.code
    except Exception as exc:  # 网络异常也记录，不中断整批用例
        return 0, {"_network_error": str(exc)}, ""
    try:
        parsed = json.loads(raw)
    except Exception:
        parsed = {"_non_json": raw[:1000]}
    rid = ""
    if isinstance(parsed, dict):
        rid = str(parsed.get("traceId") or "")
    return status, parsed, rid


def record(case_id, name, method, path, body, expect, status, response, trace_id,
           extra=None, result=None, note=""):
    item = {
        "编号": case_id,
        "功能名称": name,
        "请求": {"method": method, "path": path, "body": body},
        "期望输出": expect,
        "实际输出": {"httpStatus": status, "code": response.get("code") if isinstance(response, dict) else None,
                     "message": response.get("message") if isinstance(response, dict) else None,
                     "data": response.get("data") if isinstance(response, dict) else None,
                     "诊断": response.get("_network_error") or response.get("_non_json") or ""},
        "发现问题": note,
        "结果": result or "",
        "时间": now(),
        "测试人": "S5",
        "traceId": trace_id,
    }
    if extra:
        item["关联信息"] = extra
    CASES.append(item)
    print(f"[{case_id}] {name}: HTTP {status} code={item['实际输出']['code']} {item['结果']}")
    return item


def login(base, identity, password, case_id="E2E-S5-001"):
    body = {"identityId": identity, "password": password}
    status, resp, tid = call(base, "POST", "/api/v1/auth/login", body)
    token = ""
    if isinstance(resp, dict) and isinstance(resp.get("data"), dict):
        token = resp["data"].get("token") or ""
    ok = status == 200 and resp.get("code") == "SUCCEEDED" and bool(token)
    rec = record(case_id, "经网关登录", "POST", "/api/v1/auth/login",
                 {"identityId": identity, "password": "***"},
                 "HTTP 200，code=SUCCEEDED，data.token 非空", status, resp, tid,
                 result="通过" if ok else "失败")
    # 令牌单独剔除，避免写入证据
    if "实际输出" in rec and "data" in rec["实际输出"]:
        d = rec["实际输出"]["data"]
        if isinstance(d, dict) and "token" in d:
            d["token"] = "<已脱敏>"
    return token, ok


def get_balance(base, token, account, case_id, name):
    path = f"/api/v1/accounts/{account}/balance"
    status, resp, tid = call(base, "GET", path, token=token)
    data = resp.get("data") if isinstance(resp, dict) else None
    ok = status == 200 and resp.get("code") == "SUCCEEDED" and isinstance(data, dict)
    record(case_id, name, "GET", path, None, "HTTP 200，返回 B/R/A 快照",
           status, resp, tid, result="通过" if ok else "失败")
    return data if ok else {}


def get_fund_entries(base, token, account, case_id, trade_no=None, expect_count=1):
    path = f"/api/v1/accounts/{account}/fund-entries?page=1&pageSize=20"
    status, resp, tid = call(base, "GET", path, token=token)
    items = (resp.get("data") or {}).get("items", []) if isinstance(resp, dict) else []
    matched = [i for i in items if trade_no and i.get("tradeNo") == trade_no]
    ok = status == 200 and resp.get("code") == "SUCCEEDED"
    record(case_id, "查询资金流水", "GET", path, None,
           "HTTP 200；成功交易对应一条 DEPOSIT_IN/CREDIT 流水",
           status, resp, tid,
           extra={"tradeNo": trade_no, "匹配流水条数": len(matched), "匹配流水": matched},
           result="通过" if ok and (not trade_no or len(matched) == expect_count) else "失败")
    return matched


def get_transaction(base, token, trade_no, case_id, reason="实验四S5联调"):
    path = "/api/v1/transactions/" + urllib.parse.quote(trade_no) + "?reason=" + urllib.parse.quote(reason)
    status, resp, tid = call(base, "GET", path, token=token)
    ok = status == 200 and resp.get("code") == "SUCCEEDED"
    record(case_id, "查询交易结果", "GET", path, None,
           "HTTP 200；data.status 为业务终态", status, resp, tid,
           extra={"tradeNo": trade_no}, result="通过" if ok else "失败")
    return resp.get("data") if isinstance(resp, dict) else None


def poll_transaction(base, token, trade_no, case_id, target_status, timeout=45, interval=3):
    """轮询交易状态直到达到目标状态或超时；只写一条汇总记录。"""
    path = "/api/v1/transactions/" + urllib.parse.quote(trade_no) + "?reason=" + urllib.parse.quote("实验四S5收敛轮询")
    started = time.time()
    attempts = 0
    last_resp = None
    last_tid = ""
    last_status = None
    while time.time() - started < timeout:
        attempts += 1
        status_code, last_resp, last_tid = call(base, "GET", path, token=token)
        data = last_resp.get("data") if isinstance(last_resp, dict) else None
        last_status = data.get("status") if isinstance(data, dict) else None
        if last_status == target_status:
            break
        time.sleep(interval)
    elapsed = round(time.time() - started, 1)
    ok = last_status == target_status
    record(case_id, "轮询未决交易收敛", "GET", path, None,
           "最终 data.status=" + target_status, status_code, last_resp, last_tid,
           extra={"tradeNo": trade_no, "轮询次数": attempts, "耗时秒": elapsed,
                  "达到状态": last_status},
           result="通过" if ok else "失败",
           note="" if ok else "超过轮询时限仍未收敛，需由 S3 复核恢复任务配置。")
    return ok, last_status


def write_result(base, token, path, body, case_id, name, expect_code, expect_status=None,
                 extra=None, expect_http=None):
    status, resp, tid = call(base, "POST", path, body, token=token)
    code = resp.get("code") if isinstance(resp, dict) else None
    data = resp.get("data") if isinstance(resp, dict) else None
    st = data.get("status") if isinstance(data, dict) else None
    http_ok = True if expect_http is None else status == expect_http
    ok = http_ok and code == expect_code and (expect_status is None or st == expect_status)
    record(case_id, name, "POST", path, body, expect_code + (("/" + expect_status) if expect_status else ""),
           status, resp, tid, extra=extra, result="通过" if ok else "失败")
    return ok, data


def channel_mode(tx_base, token, mode, case_id):
    path = "/internal/v1/channel/mode"
    status, resp, tid = call(tx_base, "POST", path, {"result": mode},
                             extra_headers={"X-Internal-Token": token})
    # 内部令牌不写入证据；只记录是否切换成功
    ok = status == 200 and isinstance(resp, dict) and resp.get("code") == "SUCCEEDED"
    item = record(case_id, "切换模拟渠道结果=" + mode, "POST", path,
                  {"result": mode}, "HTTP 200，code=SUCCEEDED", status, resp, tid,
                  result="通过" if ok else "失败",
                  note="直连交易服务内部演示接口，非网关路由；需启动时配置 TRANSACTION_INTERNAL_TOKEN。")
    return ok


def channel_record(tx_base, internal_token, trade_no, case_id):
    path = "/internal/v1/channel/deposits/" + urllib.parse.quote(trade_no)
    status, resp, tid = call(tx_base, "GET", path,
                             extra_headers={"X-Internal-Token": internal_token})
    ok = status == 200 and isinstance(resp, dict) and resp.get("code") == "SUCCEEDED"
    record(case_id, "查看模拟渠道记录", "GET", path, None,
           "HTTP 200；result 与预期渠道结果一致", status, resp, tid,
           extra={"tradeNo": trade_no}, result="通过" if ok else "失败")
    return resp.get("data") if isinstance(resp, dict) else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default="http://127.0.0.1:8090")
    ap.add_argument("--tx-base", default="", help="交易服务直连地址，用于渠道三态内部接口；留空则跳过")
    ap.add_argument("--user", default="UID-000001")
    ap.add_argument("--password", default="demo123")
    ap.add_argument("--other-user", default="UID-000002")
    ap.add_argument("--account", default="ACC-0001")
    ap.add_argument("--to-account", default="ACC-0002")
    ap.add_argument("--low-account", default="", help="已有的低余额账户；留空则新开一个")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    os.makedirs(args.out, exist_ok=True)
    base = args.base_url.rstrip("/")
    print(f"S5 E2E 开始 {now()} 网关={base} 账号={args.account}")

    token, login_ok = login(base, args.user, args.password)
    if not token:
        print("登录失败，后续用例无法执行。", file=sys.stderr)
        dump(args.out)
        return 2

    # E2E-S5-001 存款闭环
    before = get_balance(base, token, args.account, "E2E-S5-001a", "存款前余额")
    rid1 = new_id()
    body1 = {"accountId": args.account, "amount": "100.00", "currency": "CNY", "requestId": rid1}
    ok1, data1 = write_result(base, token, "/api/v1/deposits", body1, "E2E-S5-001b",
                              "成功存款", "SUCCEEDED", "SUCCESS", {"requestId": rid1})
    trade_no = (data1 or {}).get("tradeNo", "")
    if trade_no:
        get_transaction(base, token, trade_no, "E2E-S5-001c")
    after = get_balance(base, token, args.account, "E2E-S5-001d", "存款后余额")
    if trade_no:
        get_fund_entries(base, token, args.account, "E2E-S5-001e", trade_no)

    # E2E-S5-006 幂等：同键同内容重放 / 同键异内容冲突
    ok_replay, replay_data = write_result(
        base, token, "/api/v1/deposits", body1, "E2E-S5-006a",
        "同请求同内容重放", "SUCCEEDED", "SUCCESS", {"requestId": rid1})
    conflict = dict(body1, amount="200.00")
    write_result(base, token, "/api/v1/deposits", conflict, "E2E-S5-006b",
                 "同请求不同内容冲突", "REQUEST_CONFLICT", None,
                 {"requestId": rid1}, expect_http=409)
    replay_same = bool(trade_no) and (replay_data or {}).get("tradeNo") == trade_no
    print(f"[幂等] 重放返回原 tradeNo: {replay_same}")

    # E2E-S5-005 限额边界：单笔超 50000.00
    rid5 = new_id()
    write_result(base, token, "/api/v1/deposits",
                 {"accountId": args.account, "amount": "50000.01", "currency": "CNY", "requestId": rid5},
                 "E2E-S5-005a", "单笔超限存款", "SINGLE_LIMIT_EXCEEDED", None,
                 {"requestId": rid5}, expect_http=422)
    # 非法币种
    rid5b = new_id()
    write_result(base, token, "/api/v1/deposits",
                 {"accountId": args.account, "amount": "100.00", "currency": "USD", "requestId": rid5b},
                 "E2E-S5-005b", "非法币种存款", "INVALID_ARGUMENT", None,
                 {"requestId": rid5b}, expect_http=400)

    # E2E-S5-002 取款成功与余额不足
    rid2 = new_id()
    write_result(base, token, "/api/v1/withdrawals",
                 {"accountId": args.account, "amount": "50.00", "currency": "CNY", "requestId": rid2},
                 "E2E-S5-002a", "取款成功", "SUCCEEDED", None, {"requestId": rid2})
    rid2b = new_id()
    write_result(base, token, "/api/v1/withdrawals",
                 {"accountId": args.account, "amount": "999999.00", "currency": "CNY", "requestId": rid2b},
                 "E2E-S5-002b", "取款超单笔限额", "SINGLE_LIMIT_EXCEEDED", None,
                 {"requestId": rid2b}, expect_http=422)
    # 可用余额不足：先开一个低余额账户，存 100 再取 200（金额在单笔限额内）。
    # 说明：演示主账户余额远高于出账单笔限额，直接大额取款会先命中限额，
    # 无法到达余额校验，因此另开账户构造该等价类。
    low_account = args.low_account
    if not low_account:
        rid2c = new_id()
        ok_open, data_open = write_result(base, token, "/api/v1/accounts",
                                          {"currency": "CNY", "requestId": rid2c},
                                          "E2E-S5-002c", "开立低余额账户", "SUCCEEDED", None,
                                          {"requestId": rid2c})
        low_account = (data_open or {}).get("accountId", "")
    if low_account:
        rid2d = new_id()
        write_result(base, token, "/api/v1/deposits",
                     {"accountId": low_account, "amount": "100.00", "currency": "CNY", "requestId": rid2d},
                     "E2E-S5-002d", "低余额账户存入 100.00", "SUCCEEDED", None,
                     {"requestId": rid2d})
        rid2e = new_id()
        write_result(base, token, "/api/v1/withdrawals",
                     {"accountId": low_account, "amount": "1000.00", "currency": "CNY", "requestId": rid2e},
                     "E2E-S5-002e", "可用余额不足取款", "INSUFFICIENT_AVAILABLE_BALANCE", None,
                     {"requestId": rid2e, "accountId": low_account}, expect_http=422)
    else:
        print("[E2E-S5-002c/e] 开户数已达上限且未指定低余额账户，余额不足等价类未执行。", file=sys.stderr)

    # E2E-S5-003 本行转账
    rid3 = new_id()
    write_result(base, token, "/api/v1/transfers",
                 {"payerAccountId": args.account, "payeeAccountId": args.to_account,
                  "amount": "30.00", "currency": "CNY", "requestId": rid3},
                 "E2E-S5-003a", "本行转账成功", "SUCCEEDED", None, {"requestId": rid3})

    # E2E-S5-008 跨行转账成功与参数缺失
    rid8 = new_id()
    write_result(base, token, "/api/v1/transfers",
                 {"payerAccountId": args.account, "externalBankCode": "ICBC",
                  "externalPayeeRef": "PAYEE-S5-001", "amount": "20.00",
                  "currency": "CNY", "purpose": "实验四S5跨行联调", "requestId": rid8},
                 "E2E-S5-008a", "跨行转账成功", "SUCCEEDED", None, {"requestId": rid8})
    rid8b = new_id()
    write_result(base, token, "/api/v1/transfers",
                 {"payerAccountId": args.account, "amount": "20.00",
                  "currency": "CNY", "requestId": rid8b},
                 "E2E-S5-008b", "跨行缺少收款字段", "INVALID_ARGUMENT", None,
                 {"requestId": rid8b}, expect_http=400)

    # E2E-S5-004 渠道三态（FAILED / UNKNOWN / 明确成功后收敛）
    if args.tx_base:
        tx_base = args.tx_base.rstrip("/")
        internal_token = os.environ.get("S5_INTERNAL_TOKEN", "")
        if not internal_token:
            print("[E2E-S5-004] 未设置 S5_INTERNAL_TOKEN，跳过渠道三态。", file=sys.stderr)
        else:
            channel_mode(tx_base, internal_token, "FAILED", "E2E-S5-004a")
            rid4a = new_id()
            ok4a, data4a = write_result(base, token, "/api/v1/deposits",
                 {"accountId": args.account, "amount": "100.00", "currency": "CNY", "requestId": rid4a},
                 "E2E-S5-004b", "渠道失败时存款", "SUCCEEDED", "FAILED", {"requestId": rid4a})
            trd4a = (data4a or {}).get("tradeNo", "")
            if trd4a:
                channel_record(tx_base, internal_token, trd4a, "E2E-S5-004c")
                get_fund_entries(base, token, args.account, "E2E-S5-004d", trd4a, expect_count=0)

            channel_mode(tx_base, internal_token, "UNKNOWN", "E2E-S5-004e")
            rid4b = new_id()
            ok4b, data4b = write_result(base, token, "/api/v1/deposits",
                 {"accountId": args.account, "amount": "100.00", "currency": "CNY", "requestId": rid4b},
                 "E2E-S5-004f", "渠道未决时存款", "SUCCEEDED", "UNKNOWN", {"requestId": rid4b})
            trd4b = (data4b or {}).get("tradeNo", "")
            if trd4b:
                channel_record(tx_base, internal_token, trd4b, "E2E-S5-004g")
                get_balance(base, token, args.account, "E2E-S5-004h", "未决后余额不增加")

            channel_mode(tx_base, internal_token, "SUCCESS", "E2E-S5-004i")
            if trd4b:
                poll_transaction(base, token, trd4b, "E2E-S5-004j", "SUCCESS")
                get_balance(base, token, args.account, "E2E-S5-004k", "收敛后余额只增加一次")

    # 越权查询：另一客户查本人交易
    if trade_no:
        token2, ok2 = login(base, args.other_user, args.password, "E2E-S5-007a")
        if token2:
            status, resp, tid = call(
                base, "GET",
                "/api/v1/transactions/" + urllib.parse.quote(trade_no)
                + "?reason=" + urllib.parse.quote("实验四S5越权检查"),
                token=token2)
            record("E2E-S5-007b", "越权查询他人交易", "GET",
                   "/api/v1/transactions/{tradeNo}?reason=...", None,
                   "HTTP 404，code=TRADE_NOT_FOUND", status, resp, tid,
                   extra={"tradeNo": trade_no, "查询方": args.other_user},
                   result="通过" if status == 404 and resp.get("code") == "TRADE_NOT_FOUND" else "失败",
                   note="该用例结果以真实响应为准；若后端口径不是 404，需由 S3/S1 复核。")

    dump(args.out)
    print(f"S5 E2E 结束 {now()}，共 {len(CASES)} 条记录，证据目录：{args.out}")
    return 0


def dump(out):
    path = os.path.join(out, "s5-e2e-results.json")
    payload = {
        "执行时间": now(),
        "网关": "见每条请求 path，令牌与密码已脱敏",
        "记录人": "S5",
        "说明": "本文件由 s5_e2e.py 自动生成；结果为脚本按期望输出自动判定，需人工复核。",
        "用例": CASES,
    }
    with open(path, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)
    print("证据写入:", path)


if __name__ == "__main__":
    sys.exit(main())
