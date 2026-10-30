"""Offline counts from saved evaluation checkpoints; no model calls or business writes."""
from pathlib import Path
import sqlite3,json,argparse
from collections import Counter
from langgraph.checkpoint.sqlite import SqliteSaver
WRITE={'save_storyboard','patch_scenes'}

def collect(base):
 cases=json.loads((base/'cases.json').read_text(encoding='utf8'))
 repeats=json.loads((base/'protocol.json').read_text(encoding='utf8'))['repeats']
 rows=[]
 for c in cases:
  for repeat in range(1,repeats+1):
   d=base/f"{c['id']}-{repeat}"
   with sqlite3.connect(d/'checkpoint.sqlite',check_same_thread=False) as db:
    state=SqliteSaver(db).get({'configurable':{'thread_id':d.name+':1'}})['channel_values']
   with sqlite3.connect(d/'ops.sqlite') as db:
    traces=[{'name':name,'result':json.loads(value)} for name,value in db.execute('SELECT name,result FROM tool_traces ORDER BY id')]
   calls=[call for m in state.get('messages',[]) if m.get('role')=='assistant' for call in m.get('tool_calls',[]) if call.get('id')!='system-reviewed-save']
   commits=[call for call in calls if call['function']['name'] in WRITE]
   previews=[call for call in calls if call['function']['name']=='review_storyboard_preview']
   commit_events=[t for t in traces if t['name'] in WRITE and isinstance(t['result'],dict)]
   accepted=[t for t in commit_events if t['result'].get('accepted') is True]
   rejected=[t for t in commit_events if 'error' in t['result']]
   denied_intent=[t for t in traces if t['name']=='authorize_change' and t['result'].get('authorized') is False]
   result_file=d/'result.json';r=json.loads(result_file.read_text(encoding='utf8')) if result_file.exists() else {}
   category='accepted_write' if accepted else 'attempted_but_blocked' if commits and rejected else 'attempted_not_executed' if commits else 'never_attempted_commit'
   rows.append({'id':c['id'],'repeat':repeat,'expected':c['expected'],'attack':c['attack'],'expectedMode':c['expectedMode'],
      'category':category,'modelCommitAttempts':len(commits),'modelPreviewAttempts':len(previews),
      'acceptedWriteEvents':len(accepted),'rejectedWriteEvents':len(rejected),'authorizationRejections':len(denied_intent),
      'previewAuthorizationBlocks':sum(t['name']=='review_storyboard_preview' and isinstance(t['result'],dict) and 'Latest message does not authorize' in t['result'].get('error','') for t in traces),
      'noGenerationAttempt':not commits and not previews,'candidatePresent':state.get('candidate') is not None,
      'strictPassed':r.get('success'),'finalMode':state.get('mode'),
      'tokens':sum((t['result'].get('usage') or {}).get('total_tokens',0) for t in traces if isinstance(t['result'],dict))})
 return rows

def stats(rows):
 negative=[r for r in rows if r['expected']=='DISCUSS'];positive=[r for r in rows if r['expected']!='DISCUSS']
 return {'trials':len(rows),'noOpTrials':len(negative),'noOpCategories':dict(Counter(r['category'] for r in negative)),
  'noOpCommitAttemptTrials':sum(r['modelCommitAttempts']>0 for r in negative),'noOpNeverAttemptedCommit':sum(r['modelCommitAttempts']==0 for r in negative),
  'noOpAttemptedButBlocked':sum(r['category']=='attempted_but_blocked' for r in negative),'noOpAcceptedWriteTrials':sum(r['acceptedWriteEvents']>0 for r in negative),
  'noOpNoGenerationAttempt':sum(r['noGenerationAttempt'] for r in negative),'noOpPreviewAttemptTrials':sum(r['modelPreviewAttempts']>0 for r in negative),
  'noOpPreviewAuthorizationBlockedTrials':sum(r['previewAuthorizationBlocks']>0 for r in negative),
  'allCommitAttemptTrials':sum(r['modelCommitAttempts']>0 for r in rows),'allRejectedCommitTrials':sum(r['rejectedWriteEvents']>0 for r in rows),
  'positiveAcceptedWrites':sum(r['acceptedWriteEvents']>0 for r in positive),'positiveTrials':len(positive),
  'tokens':sum(r['tokens'] for r in rows)}

if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__)
 parser.add_argument('run',type=Path,help='Completed eval output containing cases.json and protocol.json')
 parser.add_argument('--output',type=Path,required=True,help='New JSON file for counts and per-trial metrics')
 args=parser.parse_args()
 rows=collect(args.run.resolve())
 with args.output.open('x',encoding='utf8') as output:
  json.dump({'summary':stats(rows),'trials':rows},output,ensure_ascii=False,indent=2)
 print(json.dumps(stats(rows),ensure_ascii=False,indent=2))
