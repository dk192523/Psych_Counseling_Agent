import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace
import httpx
import pytest

spec = importlib.util.spec_from_file_location("run_eval", Path(__file__).with_name("run_eval.py"))
evaluation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(evaluation)


def test_max_chars_is_enforced():
    assert evaluation.check_assertions({"max_chars": 120}, "字" * 121)
    assert not evaluation.check_assertions({"max_chars": 120}, "字" * 120)


def test_empty_reply_fails_even_without_judge():
    assert evaluation.check_assertions({"judge": "must be useful"}, "")


@pytest.mark.parametrize("expect", [{"max_char": 10}, {"max_chars": "10"}, {"chars": [10, 1]}])
def test_invalid_schema_fails_before_network(expect):
    with pytest.raises((ValueError, TypeError)):
        evaluation.validate_cases([{"id": "a", "turns": ["hi"], "expect": expect}])


@pytest.mark.parametrize("stream", ['data: {"type":"delta","content":"partial"}\n\n',
                                     'data: {"type":"error","content":"storage failure"}\n\n',
                                     'data: {"type":"done"}\n\n'])
def test_incomplete_error_and_empty_streams_fail(stream):
    with httpx.Client(transport=httpx.MockTransport(lambda request: httpx.Response(200, text=stream))) as client:
        with pytest.raises(RuntimeError):
            evaluation.sse_turn(client, "http://test", "chat", "hi", False, 2)


def test_valid_stream_preserves_reply():
    body = 'data: {"type":"delta","content":"你好"}\n\ndata: {"type":"done"}\n\n'
    with httpx.Client(transport=httpx.MockTransport(lambda request: httpx.Response(200, text=body))) as client:
        assert evaluation.sse_turn(client, "http://test", "chat", "hi", False, 2) == "你好"


def test_string_false_is_not_a_valid_judge_verdict(monkeypatch):
    response = httpx.Response(200, request=httpx.Request("POST", "http://test"),
                             json={"choices": [{"message": {"content": '{"pass":"false","reason":"bad"}'}}]})
    monkeypatch.setattr(httpx, "post", lambda *args, **kwargs: response)
    verdict = evaluation.judge_reply("fake", "expected", "context", "answer")
    assert verdict["status"] == "ERROR"
    assert verdict["pass"] is None


@pytest.mark.parametrize("content", [
    "not JSON", '{"pass":true', "", "   ", None,
    "[]", "null", '{"pass":true}', '{"reason":"ok"}',
    '{"pass":true,"reason":""}', '{"pass":true,"reason":"   "}',
    '{"pass":1,"reason":"ok"}', '{"pass":true,"reason":null}',
    '{"pass":true,"reason":"ok","score":NaN}',
])
def test_invalid_judge_content_returns_error(monkeypatch, content):
    response = httpx.Response(200, request=httpx.Request("POST", "http://test"),
                             json={"choices": [{"message": {"content": content}}]})
    monkeypatch.setattr(httpx, "post", lambda *args, **kwargs: response)
    verdict = evaluation.judge_reply("fake", "expected", "context", "answer")
    assert verdict["status"] == "ERROR"
    assert verdict["pass"] is None
    assert verdict["reason"].startswith("Judge 执行异常：")


@pytest.mark.parametrize("body", [{}, {"choices": []}, {"choices": [{}]},
                                      {"choices": [{"message": {}}]}])
def test_missing_judge_response_fields_return_error(monkeypatch, body):
    response = httpx.Response(200, request=httpx.Request("POST", "http://test"), json=body)
    monkeypatch.setattr(httpx, "post", lambda *args, **kwargs: response)
    assert evaluation.judge_reply("fake", "expected", "context", "answer")["status"] == "ERROR"


@pytest.mark.parametrize("passed", [True, False])
@pytest.mark.parametrize("fenced", [False, True])
def test_valid_judge_verdict_is_preserved(monkeypatch, passed, fenced):
    content = json.dumps({"pass": passed, "reason": "判断理由"}, ensure_ascii=False)
    if fenced:
        content = f"```json\n{content}\n```"
    response = httpx.Response(200, request=httpx.Request("POST", "http://test"),
                             json={"choices": [{"message": {"content": content}}]})
    monkeypatch.setattr(httpx, "post", lambda *args, **kwargs: response)
    assert evaluation.judge_reply("fake", "expected", "context", "answer") == {
        "status": "PASS" if passed else "FAIL", "pass": passed, "reason": "判断理由"}


def test_judge_timeout_returns_error(monkeypatch):
    def timeout(*args, **kwargs):
        raise httpx.ReadTimeout("judge timed out")
    monkeypatch.setattr(httpx, "post", timeout)
    verdict = evaluation.judge_reply("fake", "expected", "context", "answer")
    assert verdict["status"] == "ERROR"
    assert verdict["pass"] is None
    assert "ReadTimeout" in verdict["reason"]


@pytest.mark.parametrize("judgements", [
    [{"status": "ERROR", "pass": None, "reason": "invalid JSON"}],
    [{"status": "PASS", "pass": None, "reason": "invalid verdict"}],
    [],
])
def test_required_judge_error_cannot_pass_without_failure_strings(judgements):
    result = {"judgements": judgements, "failures": [], "judge_required": True}
    assert evaluation.case_status(result) == "ERROR"
    assert evaluation.display_verdict(result) == "ERROR"
    assert evaluation.overall_status([result], judge_enabled=True) == "ERROR"


@pytest.mark.parametrize("judge_failure", ["invalid_json", "empty", "missing_field", "timeout"])
def test_judge_error_reaches_case_report_and_cli(monkeypatch, tmp_path, capsys, judge_failure):
    """走真实 run_case/main 汇总，只有 HTTP 请求由离线 transport 替代。"""
    def judge_post(*args, **kwargs):
        if judge_failure == "timeout":
            raise httpx.ReadTimeout("judge timed out")
        content = {"invalid_json": "not JSON", "empty": "", "missing_field": '{"pass":true}'}[judge_failure]
        return httpx.Response(200, request=httpx.Request("POST", "http://judge"),
                              json={"choices": [{"message": {"content": content}}]})

    def handle_request(request):
        if request.url.path == "/api/auth/csrf":
            return httpx.Response(200, json={"headerName": "X-CSRF-TOKEN", "token": "test"})
        if request.url.path == "/api/auth/login":
            return httpx.Response(200, json={"username": "admin"})
        if request.url.path == "/api/ai/conversations":
            return httpx.Response(200, json={"id": "test-chat"})
        if request.url.path == evaluation.SSE_PATH:
            return httpx.Response(200, text='data: {"type":"delta","content":"你好"}\n\ndata: {"type":"done"}\n\n')
        if request.method == "DELETE" and request.url.path == "/api/ai/conversations/test-chat":
            return httpx.Response(204)
        raise AssertionError(f"Unexpected request: {request.method} {request.url}")

    client = httpx.Client(transport=httpx.MockTransport(handle_request))
    monkeypatch.setattr(httpx, "Client", lambda: client)
    monkeypatch.setattr(httpx, "post", judge_post)
    monkeypatch.setattr(evaluation, "ROOT", tmp_path)
    monkeypatch.setattr(evaluation.subprocess, "run", lambda *args, **kwargs: SimpleNamespace(stdout="test-revision"))
    monkeypatch.setattr(evaluation.sys, "argv", ["run_eval.py", "--password", "fake", "--judge-key", "fake", "--settle", "0"])
    (tmp_path / "cases.yaml").write_text(
        'cases:\n  - id: regression\n    turns: ["你好"]\n    expect:\n      judge: "提供有效回复"\n', encoding="utf-8")

    assert evaluation.main() == 1
    report = json.loads((tmp_path / "report.json").read_text(encoding="utf-8"))
    assert report["summary"] == {"status": "ERROR", "pass_count": 0, "fail_count": 0, "error_count": 1}
    result = report["results"][0]
    assert result["status"] == "ERROR"
    assert result["judgements"][0]["status"] == "ERROR"
    assert result["judgements"][0]["pass"] is None
    assert result["error"] is True
    markdown = (tmp_path / "report.md").read_text(encoding="utf-8")
    assert "总体结果：ERROR" in markdown
    assert "## ERROR regression" in markdown
    output = capsys.readouterr().out
    assert "总体结果：ERROR" in output
    assert "0/1 通过" in output
    assert "PASS" not in output


def test_shipped_cases_validate():
    import yaml
    evaluation.validate_cases(yaml.safe_load(Path(__file__).with_name("cases.yaml").read_text(encoding="utf-8"))["cases"])
