"""Evaluation-only unchecked executor. Never used by the application or a database."""
import copy,json,time
from hashlib import sha256
from creator.agent import CreatorAgent as ProductionAgent,AgentState,SEARCH,CONTEXT,INSPECT,DISCUSS,SWITCH,REVIEW,SAVE,PATCH
from creator.models import Operation
from langgraph.graph import StateGraph,START,END

ALL=[SEARCH,CONTEXT,INSPECT,DISCUSS,SWITCH,REVIEW,SAVE,PATCH]

class UncheckedState(AgentState,total=False):
    unchecked_writes:list
    last_write_action:str

def project_patch(previous,args):
    # Apply ordinary patch semantics without validating fields, revisions, scope or evidence.
    board=copy.deepcopy(previous or {'scenes':[],'revision':0})
    board['revision']=board.get('revision',0)+1
    for field in ('title','targetAudience','musicId'):
        if field in args:board[field]=copy.deepcopy(args[field])
    for update in args.get('updates',[]):
        if not isinstance(update,dict):continue
        target=next((s for s in board.get('scenes',[]) if isinstance(s,dict) and s.get('sceneId')==update.get('sceneId')),None)
        if target is None:board.setdefault('scenes',[]).append(copy.deepcopy(update))
        else:target.update(copy.deepcopy(update))
    deleted=args.get('deleteSceneIds',[])
    board['scenes']=[s for s in board.get('scenes',[]) if not isinstance(s,dict) or s.get('sceneId') not in deleted]
    for insertion in args.get('insertions',[]):
        if isinstance(insertion,dict):board['scenes'].insert(insertion.get('index',len(board['scenes'])),copy.deepcopy(insertion.get('scene',insertion)))
    if args.get('sceneOrder'):
        ordered=[next((s for s in board['scenes'] if isinstance(s,dict) and s.get('sceneId')==id),{'sceneId':id}) for id in args['sceneOrder']]
        board['scenes']=ordered
    extras={k:v for k,v in args.items() if k not in {'baseRevision','reviewedPreviewId','updates','insertions','deleteSceneIds','sceneOrder','title','musicId','targetAudience'}}
    if extras:board['_uncheckedExtras']=copy.deepcopy(extras)
    return board


class UncheckedAgent(ProductionAgent):
    def __init__(self,*args,**kwargs):
        super().__init__(*args,**kwargs)
        checkpointer=self.graph.checkpointer
        builder=StateGraph(UncheckedState)
        builder.add_node('model',self.model_node);builder.add_node('tools',self.tools_node)
        builder.add_edge(START,'model');builder.add_edge('model','tools')
        builder.add_conditional_edges('tools',lambda state:END if state.get('candidate') is not None or state.get('reply') else 'model')
        self.graph=builder.compile(checkpointer=checkpointer)
    @staticmethod
    def available_tools(state):return ALL

    def model_node(self,state):
        # Resource/transport budgets remain identical. No mode-based executable permissions.
        if state.get('preview_draft') and state.get('preview_result') and state['tool_count']<12 and (state['decisions']>=7 or state['tool_count']>=11 or state.get('preview_attempts',0)>=4):
            name='patch_scenes' if state['operation']['parameters'].get('storyboard') else 'save_storyboard'
            args={'reviewedPreviewId':state['preview_result']['previewId']}
            if name=='patch_scenes':args['baseRevision']=state['operation']['parameters'].get('baseRevision',0)
            call={'id':'system-reviewed-save','type':'function','function':{'name':name,'arguments':json.dumps(args)}}
            self.store.trace(Operation.model_validate(state['operation']),'reviewed_draft_fallback',0,{'reason':'budget_reserved_for_save_unchecked'})
            return {'calls':[call],'messages':state['messages']+[{'role':'assistant','content':None,'tool_calls':[call]}]}
        if state['decisions']>=8 or state['tool_count']>=12 or time.time()>=state['deadline']:raise RuntimeError('Agent decision/tool/time budget exhausted')
        start=time.monotonic();response=self.model(state['messages'],ALL,min(60,max(1,state['deadline']-time.time())))
        message=response['choices'][0]['message'];calls=message.get('tool_calls') or []
        if not calls or len(calls)+state['tool_count']>12:raise ValueError('Transport requires tool calls within budget')
        self.store.trace(Operation.model_validate(state['operation']),'model',int((time.monotonic()-start)*1000),{'usage':response.get('usage'),'decision':state['decisions']+1})
        return {'calls':calls,'decisions':state['decisions']+1,'messages':state['messages']+[{'role':'assistant','content':message.get('content'),'tool_calls':calls}]}

    def tools_node(self,state):
        op=Operation.model_validate(state['operation']);params=op.parameters
        messages=list(state['messages']);mode=state.get('mode','DISCUSS');evidence=set(state['evidence_ids'])
        count=state['tool_count'];candidate=None;reply=None;review=state.get('preview_result');draft=state.get('preview_draft')
        attempts=state.get('preview_attempts',0);last_action=state.get('last_write_action');raw_writes=list(state.get('unchecked_writes',[]))
        for call in state['calls']:
            if time.time()>=state['deadline']:raise RuntimeError('Agent stage timed out')
            name=call['function']['name'];start=time.monotonic()
            try:
                raw=call['function']['arguments']
                if len(raw)>64000:raise ValueError('Transport argument size limit')
                args=json.loads(raw)
                if name=='switch_mode':mode=args['mode'];result={'mode':mode,'next':'Reply or continue with this mode on the next model turn. Switching does not authorize mutation.'}
                elif name=='finish_discussion':reply=args['reply'];result={'accepted':True,'storyboardChanged':False}
                elif name=='search_video':
                    result=self.java.evidence(op,'evidence-search',args);evidence.update(e['evidenceId'] for e in result)
                elif name=='read_context':
                    result=self.java.evidence(op,'context',args);evidence.update(e['evidenceId'] for e in result)
                elif name=='inspect_video_frame':result={'observations':'测试素材只有文字，不提供视频帧。'}
                elif name=='review_storyboard_preview':
                    draft=copy.deepcopy(args['storyboard']);h=sha256(json.dumps(draft,sort_keys=True).encode()).hexdigest()
                    review={'previewId':h,'draftSha256':h,'summary':'Routing evaluation stub; no visual claim','issues':[]}
                    attempts+=1;result={**review,'saveReviewedDraft':{'reviewedPreviewId':h}}
                    if params.get('storyboard'):result['saveReviewedDraft']['baseRevision']=params.get('baseRevision',0)
                elif name in ('save_storyboard','patch_scenes'):
                    # Store the raw request even when malformed. No production persistence occurs.
                    raw_writes.append({'tool':name,'arguments':copy.deepcopy(args),'modelCall':call.get('id')!='system-reviewed-save'})
                    if name=='save_storyboard':candidate=copy.deepcopy(args.get('storyboard',draft if 'reviewedPreviewId' in args and draft is not None else args))
                    elif set(args)<={'baseRevision','reviewedPreviewId'} and draft is not None:candidate=copy.deepcopy(draft)
                    else:candidate=project_patch(params.get('storyboard'),args)
                    last_action='CREATE' if name=='save_storyboard' else 'REVISE'
                    result={'accepted':True,'revision':candidate.get('revision') if isinstance(candidate,dict) else None,'next':'human_confirmation'}
                else:raise ValueError('No implementation for unknown transport tool')
            except (ValueError,KeyError,TypeError) as e:result={'error':str(e),'repairable':True,'category':'transport_or_materialization_not_policy'}
            count+=1;self.store.trace(op,name,int((time.monotonic()-start)*1000),result)
            messages.append({'role':'tool','tool_call_id':call['id'],'content':json.dumps(result,ensure_ascii=False)})
            if candidate is not None or reply:break
        return {'messages':messages,'mode':mode,'evidence_ids':sorted(evidence),'tool_count':count,'candidate':candidate,'reply':reply,
          'preview_result':review,'preview_draft':draft,'preview_attempts':attempts,'unchecked_writes':raw_writes,'last_write_action':last_action}

    @staticmethod
    def result(operation,state):
        candidate=state.get('candidate')
        return {'action':state.get('last_write_action') if candidate is not None else 'DISCUSS','mode':'REVISE' if candidate is not None else state.get('mode','DISCUSS'),
          'storyboard':candidate,'reply':state.get('reply') or '分镜已更新，请检查后确认生成视频。','uncheckedWrites':state.get('unchecked_writes',[])}
