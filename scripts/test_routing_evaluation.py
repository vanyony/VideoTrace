"""Offline checks for the evaluation-only executor; never calls an external model."""
import json
import sqlite3
import tempfile
import unittest
from contextlib import closing
from pathlib import Path

from langgraph.checkpoint.sqlite import SqliteSaver
from creator.store import Store
from creator.test_creator import completion, operation, settings, storyboard
from scripts.routing_eval_unchecked import UncheckedAgent


class UncheckedEvaluationTests(unittest.TestCase):
    def test_all_28_forced_no_consent_requests_are_simulated_locally(self):
        root = Path(__file__).resolve().parents[1]
        cases = json.loads((root/'creator/testdata/conversation-routing-cases.json').read_text(encoding='utf8'))
        negatives = [case for case in cases if case['expected'] == 'DISCUSS']
        self.assertEqual(len(negatives), 28)
        with tempfile.TemporaryDirectory() as directory:
            for case in negatives:
                with self.subTest(case=case['id']), closing(sqlite3.connect(':memory:', check_same_thread=False)) as db:
                    old = storyboard() if case['hasBoard'] else None
                    params = {'requirement': '30秒雨水分镜', 'instruction': case['instruction'],
                              'durationSeconds': 30, 'mode': case['mode'], 'storyboard': old,
                              'baseRevision': 1 if old else 0, 'messages': case['history']}
                    call = completion('patch_scenes', {'baseRevision': 1, 'updates': [{'sceneId': 's_2', 'headline': '强制写入测试'}]}) if old else completion('save_storyboard', {'storyboard': storyboard()})
                    def forbidden(*args):
                        raise AssertionError('Unchecked simulation must not call authorization or external services')
                    agent = UncheckedAgent(settings(directory), Store(Path(directory)/(case['id']+'.sqlite')),
                                           None, SqliteSaver(db), model=lambda *args: call, authorize=forbidden)
                    result = agent.run(operation(type='CONVERSE', parameters=params))
                    self.assertEqual(len(result['uncheckedWrites']), 1)
                    self.assertTrue(result['uncheckedWrites'][0]['modelCall'])
                    self.assertIsNotNone(result['storyboard'])
                    if old:
                        self.assertEqual(result['storyboard']['scenes'][1]['headline'], '强制写入测试')


if __name__ == '__main__':
    unittest.main()
