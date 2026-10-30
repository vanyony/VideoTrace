"""Keep the deterministic E2E model adapter aligned with conversation tools."""
import importlib.util
import io
import json
from pathlib import Path
import unittest

spec=importlib.util.spec_from_file_location('smoke_creation',Path(__file__).resolve().parents[1]/'scripts/smoke-creation.py')
smoke=importlib.util.module_from_spec(spec);spec.loader.exec_module(smoke)


def request(body):
    raw=json.dumps(body).encode();handler=object.__new__(smoke.ModelStub)
    handler.path='/v1/chat/completions';handler.headers={'Content-Length':str(len(raw))}
    handler.rfile=io.BytesIO(raw);handler.wfile=io.BytesIO()
    handler.send_response=lambda *_:None;handler.send_header=lambda *_:None;handler.end_headers=lambda:None
    handler.do_POST();return json.loads(handler.wfile.getvalue())


class SmokeModelStubTests(unittest.TestCase):
    def test_creation_switch_and_question_while_revising(self):
        from .agent import SWITCH,SEARCH,CONTEXT,DISCUSS,PATCH
        messages=[{'role':'system','content':'fixture'},{'role':'user','content':json.dumps({'instruction':'按三个变化直接生成分镜'})}]
        response=request({'messages':messages,'tools':[SWITCH,SEARCH,CONTEXT,DISCUSS]})
        call=response['choices'][0]['message']['tool_calls'][0]['function']
        self.assertEqual(call['name'],'switch_mode');self.assertEqual(json.loads(call['arguments']),{'mode':'CREATE'})
        messages[1]['content']=json.dumps({'instruction':'为什么第二段这么写？先不要修改或生成视频'})
        messages += [{'role':'tool','content':'[]'},{'role':'tool','content':'[]'}]
        response=request({'messages':messages,'tools':[SWITCH,SEARCH,CONTEXT,DISCUSS,PATCH]})
        self.assertEqual(response['choices'][0]['message']['tool_calls'][0]['function']['name'],'finish_discussion')

    def test_authorization_stub_is_scoped_to_current_instruction(self):
        for instruction,expected in [('按三个变化直接生成分镜',True),('先解释，不要改',False)]:
            response=request({'messages':[{'role':'system','content':'写操作授权检查员'},
                     {'role':'user','content':json.dumps({'latestUserMessage':instruction,'requestedAction':'CREATE'})}]})
            content=json.loads(response['choices'][0]['message']['content'])
            self.assertEqual(content['authorized'],expected)
