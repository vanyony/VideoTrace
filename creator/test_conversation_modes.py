import copy
from contextlib import closing
from pathlib import Path
import sqlite3
import tempfile
import unittest
from langgraph.checkpoint.sqlite import SqliteSaver
from .agent import CreatorAgent
from .test_creator import Java,completion,operation,settings,storyboard
from .store import Store


class ConversationModeTests(unittest.TestCase):
    def run_turn(self,responses,mode='DISCUSS',board=None,authorized=False,instruction='为什么这样设计？'):
        with tempfile.TemporaryDirectory() as directory,closing(sqlite3.connect(':memory:',check_same_thread=False)) as db:
            offered=[];turns=iter(responses);checks=[]
            def model(messages,tools,timeout):
                offered.append([t['function']['name'] for t in tools]);return next(turns)
            def authorize(op,action,timeout):
                checks.append(action);return {'authorized':authorized,'action':action,'reason':'unit fixture'}
            params={'requirement':'视频创作','instruction':instruction,'durationSeconds':30,'mode':mode,
                    'baseRevision':board['revision'] if board else 0,'storyboard':board,'messages':[]}
            op=operation(type='CONVERSE',parameters=params)
            agent=CreatorAgent(settings(directory),Store(Path(directory)/'ops.sqlite'),Java(),SqliteSaver(db),model=model,authorize=authorize)
            result=agent.run(op)
            return result,offered,checks

    def test_question_in_revision_mode_replies_without_switch_or_authorization_call(self):
        result,offered,checks=self.run_turn([completion('finish_discussion',{'reply':'这是为了让信息更清楚。'})],mode='REVISE',board=storyboard())
        self.assertEqual(result['action'],'DISCUSS');self.assertEqual(result['mode'],'REVISE')
        self.assertIsNone(result['storyboard']);self.assertEqual(checks,[])
        self.assertIn('switch_mode',offered[0]);self.assertIn('patch_scenes',offered[0])

    def test_same_mode_revision_needs_current_message_authorization(self):
        responses=[completion('patch_scenes',{'baseRevision':1,'updates':[{'sceneId':'s_2','headline':'新标题'}]}),completion('finish_discussion',{'reply':'只解释，不修改。'})]
        result,_,checks=self.run_turn(responses,mode='REVISE',board=storyboard())
        self.assertIsNone(result['storyboard']);self.assertEqual(checks,['REVISE'])

    def test_switch_does_not_authorize_mutation_and_batch_cannot_bypass(self):
        first=completion('switch_mode',{'mode':'DISCUSS'})
        first['choices'][0]['message']['tool_calls']+=completion('patch_scenes',{'baseRevision':1,'updates':[{'sceneId':'s_2','headline':'越权'}]})['choices'][0]['message']['tool_calls']
        result,_,checks=self.run_turn([first,completion('finish_discussion',{'reply':'先讨论。'})],mode='REVISE',board=storyboard())
        self.assertEqual(result['mode'],'DISCUSS');self.assertIsNone(result['storyboard']);self.assertEqual(checks,[])

    def test_authorized_revision_stays_in_mode_without_forced_classification(self):
        result,offered,checks=self.run_turn([completion('patch_scenes',{'baseRevision':1,'updates':[{'sceneId':'s_2','headline':'新标题'}]})],mode='REVISE',board=storyboard(),authorized=True,instruction='把第二幕标题改成新标题')
        self.assertEqual(result['storyboard']['revision'],2);self.assertEqual(result['mode'],'REVISE')
        self.assertEqual(checks,['REVISE']);self.assertNotIn('choose_action',offered[0])
