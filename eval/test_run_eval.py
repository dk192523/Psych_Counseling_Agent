import importlib.util
from pathlib import Path
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
    with pytest.raises(ValueError):
        evaluation.judge_reply("fake", "expected", "context", "answer")


def test_shipped_cases_validate():
    import yaml
    evaluation.validate_cases(yaml.safe_load(Path(__file__).with_name("cases.yaml").read_text(encoding="utf-8"))["cases"])
