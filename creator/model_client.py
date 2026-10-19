"""Configurable OpenAI-compatible chat transport for the creator workflow."""
from urllib.parse import urlsplit

import httpx


class ChatModelClient:
    def __init__(self, settings):
        settings.validate_model()
        self.settings = settings

    def __call__(self, messages, tools, timeout):
        return self.chat(messages, timeout, tools=tools)

    def chat(self, messages, timeout, *, tools=None):
        settings = self.settings
        if not settings.llm_key:
            raise RuntimeError("Creator LLM API key is not configured")
        options = {"tool_choice": "required", "temperature": 0.3, "max_tokens": 5000}
        # Preserve compatibility with existing DeepSeek deployments.
        if urlsplit(settings.llm_url).hostname == "api.deepseek.com":
            options["thinking"] = {"type": "disabled"}
        options.update(settings.llm_options)
        # null removes optional defaults for models that reject those parameters.
        payload = {k: v for k, v in options.items() if v is not None}
        payload.update(model=settings.llm_model, messages=messages)
        if tools is not None:
            payload["tools"] = tools
        else:
            payload.pop("tool_choice", None)
        with httpx.Client(timeout=timeout, trust_env=False) as client:
            response = client.post(settings.llm_url.rstrip("/") + "/chat/completions",
                                   headers={"Authorization": "Bearer " + settings.llm_key}, json=payload)
            response.raise_for_status()
            if len(response.content) > 256000:
                raise ValueError("model response too large")
            return response.json()
