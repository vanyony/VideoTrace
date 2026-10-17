import json
import os
import unittest
from dataclasses import replace
from unittest.mock import patch

import httpx

from .model_client import ChatModelClient
from .settings import Settings
from .test_creator import settings


class ModelClientTests(unittest.TestCase):
    def test_switch_endpoint_model_and_options_without_changing_agent(self):
        messages = [{"role": "user", "content": "test"}]
        tools = [{"type": "function", "function": {"name": "search_video"}}]
        for base, model, options in [
            ("https://api.deepseek.com", "configured-model-a", {}),
            ("https://example.test/v1/", "configured-model-b",
             {"temperature": None, "max_tokens": None, "max_completion_tokens": 1234,
              "tool_choice": "auto", "enable_thinking": False}),
        ]:
            with self.subTest(base=base):
                cfg = replace(settings("."), llm_url=base, llm_model=model, llm_options=options)

                def respond(request):
                    self.assertEqual(str(request.url), base.rstrip("/") + "/chat/completions")
                    self.assertEqual(request.headers["Authorization"], "Bearer test")
                    body = json.loads(request.content)
                    self.assertEqual(body["model"], model)
                    self.assertEqual(body["messages"], messages)
                    self.assertEqual(body["tools"], tools)
                    if "deepseek.com" in base:
                        self.assertEqual(body["thinking"], {"type": "disabled"})
                        self.assertEqual(body["tool_choice"], "required")
                    else:
                        self.assertNotIn("thinking", body)
                        self.assertNotIn("temperature", body)
                        self.assertNotIn("max_tokens", body)
                        self.assertEqual(body["max_completion_tokens"], 1234)
                        self.assertEqual(body["tool_choice"], "auto")
                    return httpx.Response(200, json={"choices": []})

                client = httpx.Client(transport=httpx.MockTransport(respond))
                with patch("creator.model_client.httpx.Client", return_value=client):
                    self.assertEqual(ChatModelClient(cfg)(messages, tools, 5), {"choices": []})

    def test_configuration_loads_and_rejects_workflow_overrides(self):
        with patch.dict(os.environ, {"CREATOR_LLM_BASE_URL": "https://example.test/v1",
                                    "CREATOR_LLM_MODEL": "custom-model",
                                    "CREATOR_LLM_OPTIONS": '{"temperature":null}'}, clear=True):
            cfg = Settings.from_env()
            self.assertEqual(cfg.llm_model, "custom-model")
            self.assertEqual(cfg.llm_options, {"temperature": None})
            cfg.validate_model()
        for options in ([], {"messages": []}, {"stream": True}, {"model": "override"},
                        {"tools": []}, {"n": 2}, {"tool_choice": "none"}):
            with self.subTest(options=options), self.assertRaises(ValueError):
                ChatModelClient(replace(settings("."), llm_options=options))


if __name__ == "__main__":
    unittest.main()
