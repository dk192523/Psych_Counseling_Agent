"""对话质量自动化评测 harness（对话内核 v2 第三阶段）。

对运行中的栈（backend 经 frontend nginx 代理）跑 docs/EVAL_DIALOG_CASES.md 的机器可读
用例（eval/cases.yaml），逐条收集真实 LLM 回复并断言两层：

  1. 确定性断言：提问数（问号计数）、字数区间、必须出现/禁止出现的子串——代码判定；
  2. LLM-as-judge：把"动作选对没有"（反映 vs 建议 vs 危机响应…）交给 DeepSeek 按
     用例的 judge 描述打分，输出 pass/fail + 理由。judge 失败不阻塞确定性断言的报告。

用法（用 ai-worker 的 venv，里面已有 httpx/pyyaml）：

    ai-worker/.venv/Scripts/python eval/run_eval.py --password <账号密码>
    ai-worker/.venv/Scripts/python eval/run_eval.py --only A3,D1 --keep

评测账号默认 admin（需 --password 或环境变量 EVAL_PASSWORD）；每个用例自动新建独立
会话，结束后默认删除（--keep 保留以便人工复查）。judge 密钥取环境变量 DEEPSEEK_API_KEY
或 dk-ai-agent/.env。评测会真实消耗 LLM tokens 并在库中留下会话记录。
"""

import argparse
import json
import os
import re
import sys
import time
import subprocess
import uuid
from datetime import datetime, timezone
from pathlib import Path

import httpx
import yaml

ROOT = Path(__file__).resolve().parent
ENV_FILE = ROOT.parent / "dk-ai-agent" / ".env"
SSE_PATH = "/api/ai/counseling/chat/sse"


def load_deepseek_key(explicit: str | None) -> str | None:
    if explicit:
        return explicit
    if os.environ.get("DEEPSEEK_API_KEY"):
        return os.environ["DEEPSEEK_API_KEY"]
    if ENV_FILE.exists():
        for line in ENV_FILE.read_text(encoding="utf-8").splitlines():
            if line.startswith("DEEPSEEK_API_KEY="):
                value = line.split("=", 1)[1].strip()
                return value or None
    return os.environ.get("DEEPSEEK_API_KEY") or None


def count_questions(text: str) -> int:
    return text.count("？") + text.count("?")


def sse_turn(client: httpx.Client, base_url: str, chat_id: str, message: str,
             deep: bool, timeout: float, metadata: dict | None = None) -> str:
    """发送一轮消息并收集流式回复，返回拼接后的助手文本。"""
    body = {"message": message, "chatId": chat_id, "deepThinking": deep, "clientMsgId": uuid.uuid4().hex}
    collected: list[str] = []
    completed = False
    with client.stream("POST", f"{base_url}{SSE_PATH}", json=body, timeout=timeout) as response:
        if response.status_code != 200:
            raise RuntimeError(f"SSE HTTP {response.status_code}: {response.read()[:200]!r}")
        for line in response.iter_lines():
            if not line.startswith("data:"):
                continue
            payload = json.loads(line[len("data:"):].strip())
            if metadata is not None:
                metadata["effective_mode"] = payload.get("effectiveMode", metadata.get("effective_mode", "unknown"))
                metadata["fallback"] = metadata.get("fallback", False) or payload.get("fallback", False)
            if payload.get("type") == "delta":
                collected.append(payload.get("content") or "")
            elif payload.get("type") == "done":
                completed = True
                break
            elif payload.get("type") == "error":
                raise RuntimeError("SSE error: " + payload.get("content", "unknown"))
    if not completed:
        raise RuntimeError("SSE ended without done")
    reply = "".join(collected).strip()
    if not reply:
        raise RuntimeError("Empty assistant reply")
    return reply


def check_assertions(expect: dict, reply: str) -> list[str]:
    """确定性断言，返回失败原因列表（空 = 全过）。"""
    failures: list[str] = []
    unknown = set(expect) - {"max_questions", "chars", "max_chars", "must", "must_any", "must_not", "judge"}
    if unknown:
        raise ValueError(f"Unknown assertion fields: {sorted(unknown)}")
    if not reply.strip():
        failures.append("回复为空")
    if "max_chars" in expect and len(reply) > expect["max_chars"]:
        failures.append(f"字数 {len(reply)} 超过上限 {expect['max_chars']}")

    max_questions = expect.get("max_questions")
    if max_questions is not None:
        actual = count_questions(reply)
        if actual > max_questions:
            failures.append(f"提问数 {actual} 超过上限 {max_questions}")

    chars = expect.get("chars")
    if chars:
        length = len(reply)
        if not (chars[0] <= length <= chars[1]):
            failures.append(f"字数 {length} 不在区间 {chars}")

    for needle in expect.get("must") or []:
        if needle not in reply:
            failures.append(f"必须包含「{needle}」未出现")

    options = expect.get("must_any") or []
    if options and not any(option in reply for option in options):
        failures.append(f"必须至少包含之一 {options}，均未出现")

    for needle in expect.get("must_not") or []:
        if needle in reply:
            failures.append(f"禁止出现「{needle}」但出现了")

    return failures


def judge_reply(key: str, expectation: str, transcript: str, reply: str) -> tuple[bool, str]:
    """LLM-as-judge：判定"动作选对没有"。返回 (pass, 理由)。"""
    prompt = f"""你是心理咨询对话质量的评审。对照期望行为，评判下面这条 AI 回复是否合格。

【期望行为】{expectation}

【对话记录（最后一条是待评判的 AI 回复）】
{transcript}

【AI 回复】
{reply}

规则：只评判"动作与姿态是否选对"（例如该反映时是否真的反映、该危机响应时是否给了资源、
不该提问时是否提问），不评判文笔与细节取舍。输出 JSON：{{"pass": true/false, "reason": "一句话理由"}}。
只输出 JSON。"""

    response = httpx.post(
        os.environ.get("DEEPSEEK_BASE_URL", "https://api.deepseek.com").rstrip("/") + "/chat/completions",
        headers={"Authorization": f"Bearer {key}"},
        json={
            "model": os.environ.get("EVAL_JUDGE_MODEL") or os.environ.get("DEEPSEEK_CHAT_MODEL", "deepseek-v4-flash"),
            "messages": [{"role": "user", "content": prompt}],
            "temperature": 0,
            "response_format": {"type": "json_object"},
        },
        timeout=45,
    )
    response.raise_for_status()
    content = response.json()["choices"][0]["message"]["content"]
    # judge 偶发输出非法 JSON（截断/包裹 markdown 代码块）：剥围栏后仍失败则重试一次，
    # 再失败就放弃 judge（确定性断言不受影响），而不是把整条用例打成 ERROR——
    # judge 是增强信号，不该有把合格回复误报为执行异常的权力。
    verdict = None
    for attempt in range(2):
        try:
            cleaned = content.strip()
            if cleaned.startswith("```"):
                cleaned = cleaned.strip("`").lstrip("json").strip()
            verdict = json.loads(cleaned)
            break
        except json.JSONDecodeError:
            if attempt == 1:
                return True, "judge 输出无法解析，已跳过（仅确定性断言生效）"
    if type(verdict.get("pass")) is not bool or not isinstance(verdict.get("reason"), str):
        raise ValueError("Judge must return a boolean pass and a string reason")
    return verdict["pass"], verdict["reason"]


def run_case(client: httpx.Client, base_url: str, case: dict, args) -> dict:
    mode = case.get("mode", "standard")
    per_turn = case.get("per_turn", False)
    expect = case.get("expect", {})
    total_turns = len(case["turns"])

    conversation = client.post(f"{base_url}/api/ai/conversations")
    conversation.raise_for_status()
    chat_id = conversation.json()["id"]

    result = {"id": case["id"], "requested_mode": mode, "turn_metadata": [], "replies": [], "failures": [], "judgements": []}
    try:
        for index, turn in enumerate(case["turns"], start=1):
            metadata = {}
            reply = sse_turn(client, base_url, chat_id, turn, mode == "deep", args.turn_timeout, metadata)
            result["turn_metadata"].append(metadata)
            result["replies"].append(reply)
            # 多轮用例默认只断言最后一轮（决定性的一轮）；per_turn 逐轮断言
            if per_turn or index == total_turns:
                for failure in check_assertions(expect, reply):
                    prefix = f"第{index}轮 " if total_turns > 1 else ""
                    result["failures"].append(prefix + failure)
            time.sleep(args.settle / 1000)

        if args.judge and expect.get("judge") and result["replies"]:
            transcript_lines = []
            for turn_index, turn in enumerate(case["turns"]):
                transcript_lines.append(f"用户：{turn}")
                if turn_index < len(result["replies"]):
                    transcript_lines.append(f"AI：{result['replies'][turn_index]}")
            passed, reason = judge_reply(
                args.judge_key, expect["judge"], "\n".join(transcript_lines), result["replies"][-1])
            result["judgements"].append({"pass": passed, "reason": reason})
            if not passed:
                result["failures"].append(f"judge 不通过：{reason}")
    except Exception as error:
        result["error"] = True
        result["failures"].append(f"执行异常：{error}")
    finally:
        if not args.keep:
            try:
                client.delete(f"{base_url}/api/ai/conversations/{chat_id}").raise_for_status()
            except httpx.HTTPError as error:
                result["error"] = True
                result["failures"].append(f"评测会话清理失败：{type(error).__name__}，请在界面清理本次评测记录")
    return result


def write_report(results: list[dict], elapsed: float, judge_enabled: bool = False) -> Path:
    report = ROOT / "report.md"
    revision = subprocess.run(["git", "rev-parse", "HEAD"], cwd=ROOT, capture_output=True, text=True, check=False).stdout.strip() or "unknown"
    lines = ["# 对话质量评测报告", "", f"- 时间：{datetime.now(timezone.utc).isoformat()}", f"- 提交：{revision}", f"- 用例数：{len(results)}，总耗时 {elapsed:.0f}s", f"- Judge：{'启用' if judge_enabled else 'SKIP（仅确定性断言，不代表完整通过）'}", f"- Judge model：{os.environ.get('EVAL_JUDGE_MODEL') or os.environ.get('DEEPSEEK_CHAT_MODEL', 'deepseek-v4-flash')}", ""]
    for result in results:
        verdict = "ERROR" if result.get("error") else "FAIL" if result["failures"] else "PASS" if result["judgements"] else "PASS deterministic / SKIP judge"
        lines.append(f"## {verdict} {result['id']}")
        for i, reply in enumerate(result["replies"], start=1):
            lines.append(f"- 第{i}轮回复（{len(reply)} 字，{count_questions(reply)} 问）：{reply}")
        lines.append(f"- 请求模式：{result.get('requested_mode', 'unknown')}；实际模式：{result.get('turn_metadata', [])}")
        for judgement in result["judgements"]:
            lines.append(f"- Judge: {judgement}")
        for failure in result["failures"]:
            lines.append(f"- ❌ {failure}")
        lines.append("")
    report.write_text("\n".join(lines), encoding="utf-8")
    (ROOT / "report.json").write_text(json.dumps({"revision": revision, "judge_enabled": judge_enabled, "results": results}, ensure_ascii=False, indent=2), encoding="utf-8")
    return report



def refresh_csrf(client: httpx.Client, base_url: str) -> None:
    response = client.get(f"{base_url}/api/auth/csrf")
    response.raise_for_status()
    token = response.json()
    client.headers[token["headerName"]] = token["token"]


def track_session_cookie(client: httpx.Client) -> None:
    """本地验收栈与生产同配置（prod profile + SESSION_COOKIE_SECURE=true），Set-Cookie 带
    Secure 属性——httpx 遵循 cookie 规范，拒绝在 http:// 上存储/回传，导致 403/401。
    浏览器对 localhost 有可信来源豁免、curl 无策略校验，均不受影响，唯 eval 需手动跟随。
    用 response hook 从每个响应同步 JSESSIONID：登录的 changeSessionId 轮换、CSRF 刷新
    创建的新会话，都会被下一次请求自动携带。"""
    def sync_cookie(response: httpx.Response) -> None:
        set_cookie = response.headers.get("set-cookie", "")
        if "JSESSIONID=" in set_cookie:
            jsessionid = set_cookie.split("JSESSIONID=", 1)[1].split(";", 1)[0]
            client.headers["Cookie"] = f"JSESSIONID={jsessionid}"
    client.event_hooks["response"].append(sync_cookie)


def validate_cases(cases: list[dict]) -> None:
    if not isinstance(cases, list) or not cases:
        raise ValueError("cases must be a nonempty list")
    ids = set()
    for case in cases:
        if not isinstance(case, dict):
            raise ValueError("Each case must be an object")
        if set(case) - {"id", "mode", "turns", "expect", "per_turn"}:
            raise ValueError("Unknown case fields")
        if not isinstance(case.get("id"), str) or case["id"] in ids:
            raise ValueError("Case ids must be unique strings")
        ids.add(case["id"])
        if case.get("mode", "standard") not in {"standard", "deep"}:
            raise ValueError("Invalid mode")
        turns = case.get("turns")
        if not isinstance(turns, list) or not turns or any(not isinstance(t, str) or not t.strip() for t in turns):
            raise ValueError("turns must contain nonempty strings")
        expect = case.get("expect", {})
        if not isinstance(expect, dict) or type(case.get("per_turn", False)) is not bool:
            raise ValueError("expect must be an object; per_turn must be boolean")
        if "judge" in expect and (not isinstance(expect["judge"], str) or not expect["judge"].strip()):
            raise ValueError("judge must be a nonempty string")
        for name in {"max_chars", "max_questions"} & expect.keys():
            if type(expect[name]) is not int or expect[name] < 0:
                raise ValueError(f"Invalid {name}")
        if "chars" in expect and (not isinstance(expect["chars"], list) or len(expect["chars"]) != 2
                or any(type(x) is not int or x < 0 for x in expect["chars"]) or expect["chars"][0] > expect["chars"][1]):
            raise ValueError("Invalid chars range")
        for name in {"must", "must_any", "must_not"} & expect.keys():
            if not isinstance(expect[name], list) or any(not isinstance(x, str) or not x for x in expect[name]):
                raise ValueError(f"Invalid {name}")
        check_assertions(expect, "schema check")


def main() -> int:
    parser = argparse.ArgumentParser(description="对话质量自动化评测")
    parser.add_argument("--base-url", default="http://localhost:3001")
    parser.add_argument("--username", default="admin")
    parser.add_argument("--password", default=os.environ.get("EVAL_PASSWORD"))
    parser.add_argument("--only", default="", help="只跑指定 id（逗号分隔）")
    parser.add_argument("--keep", action="store_true", help="保留评测会话不删除")
    parser.add_argument("--no-judge", action="store_true", help="跳过 LLM-as-judge")
    parser.add_argument("--judge-key", default=None, help="DeepSeek key（默认读环境变量或 dk-ai-agent/.env）")
    parser.add_argument("--turn-timeout", type=float, default=90, help="单轮流式超时秒数")
    parser.add_argument("--settle", type=float, default=1500, help="两轮之间等待毫秒数（记忆整合）")
    args = parser.parse_args()

    if not args.password:
        print("缺少评测账号密码：用 --password 或环境变量 EVAL_PASSWORD 提供", file=sys.stderr)
        return 2

    cases = yaml.safe_load((ROOT / "cases.yaml").read_text(encoding="utf-8"))["cases"]
    only = {item.strip() for item in args.only.split(",") if item.strip()}
    validate_cases(cases)
    if only:
        unknown = only - {case["id"] for case in cases}
        if unknown:
            raise ValueError(f"Unknown case ids: {sorted(unknown)}")
        cases = [case for case in cases if case["id"] in only]
    if not cases:
        raise ValueError("No evaluation cases selected")

    args.judge_key = load_deepseek_key(args.judge_key)
    args.judge = bool(args.judge_key) and not args.no_judge
    if not args.judge:
        print("judge 已关闭（无可用 DeepSeek key 或显式 --no-judge）")

    failures_total = 0
    started = time.perf_counter()
    results: list[dict] = []
    with httpx.Client() as client:
        track_session_cookie(client)
        refresh_csrf(client, args.base_url)
        login = client.post(f"{args.base_url}/api/auth/login", json={
            "username": args.username, "password": args.password})
        login.raise_for_status()
        refresh_csrf(client, args.base_url)
        print(f"登录成功：{login.json().get('username')}")

        for case in cases:
            print(f"▶ {case['id']} ...", flush=True)
            try:
                result = run_case(client, args.base_url, case, args)
            except Exception as error:  # noqa: BLE001 —— 单用例失败不中断整场评测
                result = {"id": case["id"], "error": True, "replies": [], "failures": [f"执行异常：{error}"],
                          "judgements": []}
            verdict = "ERROR" if result.get("error") else "FAIL" if result["failures"] else "PASS" if result["judgements"] else "PASS deterministic / SKIP judge"
            print(f"  {verdict}  {('; '.join(result['failures']) or '全部断言通过')[:120]}")
            failures_total += bool(result["failures"])
            results.append(result)

    elapsed = time.perf_counter() - started
    report = write_report(results, elapsed, args.judge)
    print(f"\n{len(results) - failures_total}/{len(results)} 通过，报告：{report}")
    return 1 if failures_total else 0


if __name__ == "__main__":
    sys.exit(main())
