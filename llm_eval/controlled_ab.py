import pathlib,json,time,requests,subprocess as sp,statistics,re,hashlib
R=pathlib.Path(__file__).parent
# Load only declarations, never execute the previous benchmark.
ns={'__file__':str(R/'bench.py')}; source=(R/'bench.py').read_text(encoding='utf-8'); exec(source[:source.index('results=[]')],ns)
cases=ns['cases']; schemaA=ns['schema']; protocolA=ns['protocol']
ADB=ns['ADB']; serial=ns['serial']
def adb(*args):
 p=sp.run([ADB,'-s',serial,*args],capture_output=True,timeout=90)
 if p.returncode: raise RuntimeError(p.stderr.decode(errors='replace'))
 return p.stdout.decode(errors='replace')
common='你只做手机任务语义解析，不执行操作。创建CREATE；取消CANCEL；查询QUERY；暂停PAUSE；继续RESUME；关闭闹钟DISABLE；打开应用OPEN。取消绝不创建，查询不得修改。不知道对象时保持引用，不猜对象。上下文：刚才创建了五分钟倒计时，其引用为timer_last。条件不修改蓝牙时返回QUERY/BLUETOOTH。'
protocolB=common+'只输出JSON：{"tasks":[{"action":"动作","target":"对象类型","rawParameters":"用户原文中的对象参数","objectReference":null}]}。不要换算时间，不要生成执行步骤。rawParameters原样保留时长、时间、应用名称、分身、功能及条件原文。objectReference只在指代刚才对象时填写timer_last，否则null。'
itemB={'type':'object','properties':{'action':schemaA['properties']['tasks']['items']['properties']['action'],'target':schemaA['properties']['tasks']['items']['properties']['target'],'rawParameters':{'type':'string'},'objectReference':{'type':['string','null']}},'required':['action','target','rawParameters','objectReference'],'additionalProperties':False}
schemaB={'type':'object','properties':{'tasks':{'type':'array','items':itemB}},'required':['tasks'],'additionalProperties':False}

def num(s):
 if s.isdigit(): return int(s)
 d={'一':1,'二':2,'三':3,'四':4,'五':5,'六':6,'七':7,'八':8,'九':9,'零':0,'两':2}
 if s=='十': return 10
 if '十' in s:
  a,b=s.split('十'); return (d.get(a,1)*10)+d.get(b,0)
 return d.get(s)

def normalize(t,mode,text):
 if mode=='A': return t
 raw=t.get('rawParameters',''); p={}; result={'action':t.get('action'),'target':t.get('target'),'parameters':p,'conditions':[],'targetIdentity':t.get('objectReference')}
 # Raw evidence must come from the user text. This layer cannot repair a wrong model action/target.
 if raw and raw not in text: result['unfaithfulRaw']=True
 m=re.search(r'([零一二两三四五六七八九十\d]+)(分钟|秒)',raw)
 if m and num(m[1]) is not None: p['seconds']=num(m[1])*(60 if m[2]=='分钟' else 1)
 m=re.search(r'([零一二两三四五六七八九十\d]+)点',raw)
 if m and num(m[1]) is not None: p.update(hour=num(m[1]),minute=0)
 if '三星' in raw: p['appName']='三星应用商店'
 if 'Google' in raw: p['appName']='Google Play'
 if '微信' in raw: p['appName']='微信'
 if '分身' in raw: p['profile']='clone'
 if '扫一扫' in raw: p['feature']='扫一扫'
 if '如果' in raw and '不要修改' in raw: result['conditions']=[raw]
 return result

def score(t,i,mode):
 text,action,target=cases[i]; t=normalize(t,mode,text); p=t.get('parameters',{}); core=t.get('action')==action and t.get('target')==target
 ok=core and not t.get('unfaithfulRaw'); issues=[]
 if not core: issues.append('action_target')
 if i in [0,1,2,3,14]:
  expected=180 if i==3 else 300
  if p.get('seconds')!=expected: ok=False; issues.append('duration')
 if i in [5,6,7]:
  if p.get('hour')!=(11 if i==7 else 10) or p.get('minute',0)!=0: ok=False; issues.append('clock_time')
 if i in [4,14] and t.get('targetIdentity')!='timer_last': ok=False; issues.append('reference')
 if i in [9,10,11,12]:
  name=p.get('appName','').lower()
  match=('三星' in name or 'galaxy' in name) if i==9 else ('google' in name) if i==10 else ('微信' in name or 'wechat' in name)
  if not match: ok=False; issues.append('app_identity')
 if i==11 and not p.get('profile'): ok=False; issues.append('profile')
 if i==12 and p.get('feature') not in ['扫一扫','扫码','SCAN']: ok=False; issues.append('feature')
 if i==13 and not t.get('conditions'): ok=False; issues.append('condition')
 a=t.get('action'); mut={'CREATE','DISABLE','DELETE','PAUSE','RESUME','ENABLE','SET'}
 reverse=(action in ['CANCEL','DISABLE','PAUSE','RESUME','QUERY'] and a=='CREATE') or (action=='QUERY' and a in mut) or (action=='CREATE' and a in ['CANCEL','DELETE','DISABLE'])
 return {'core':core,'semantic':ok,'reverse':reverse,'issues':issues,'canonical':t}

client=requests.Session(); client.trust_env=False
results=[]
for model in ['Qwen3-0.6B-Q4_K_M.gguf','Qwen3-1.7B-Q4_K_M.gguf']:
 cmd='cd /data/local/tmp/semantic-eval; LD_LIBRARY_PATH=. nohup ./llama-server -m '+model+' -c 4096 -t 4 -ngl 0 -np 1 --host 127.0.0.1 --port 18765 --jinja --chat-template-file qwen3_official.jinja --reasoning off --cache-ram 0 > ab_server.log 2>&1 < /dev/null & echo $!'
 start=time.perf_counter(); pid=adb('shell',cmd).strip(); adb('forward','tcp:18765','tcp:18765')
 try:
  for _ in range(200):
   try:
    if client.get('http://127.0.0.1:18765/health',timeout=2).status_code==200: break
   except requests.RequestException: pass
   time.sleep(.5)
  load=time.perf_counter()-start
  props=client.get('http://127.0.0.1:18765/props',timeout=15).json()
  (R/(model+'.ab.props.json')).write_text(json.dumps(props,ensure_ascii=False,indent=2),encoding='utf-8')
  rows=[]
  for i,(text,_,_) in enumerate(cases):
   # Alternate order to reduce consistent thermal/cache bias.
   for mode in (['A','B'] if i%2==0 else ['B','A']):
    messages=[{'role':'system','content':protocolA if mode=='A' else protocolB},{'role':'user','content':text+' /no_think'}]
    if i==0:
     rendered=client.post('http://127.0.0.1:18765/apply-template',json={'messages':messages,'add_generation_prompt':True,'chat_template_kwargs':{'enable_thinking':False}},timeout=30)
     (R/(model+'.'+mode+'.template.txt')).write_text(rendered.text,encoding='utf-8')
    payload={'messages':messages,'temperature':0,'top_k':0,'top_p':1,'min_p':0,'repeat_penalty':1,'seed':42,'max_tokens':240,'stream':False,'cache_prompt':False,'chat_template_kwargs':{'enable_thinking':False},'response_format':{'type':'json_schema','json_schema':{'name':'plan','schema':schemaA if mode=='A' else schemaB}}}
    t0=time.perf_counter(); row={'model':model,'mode':mode,'case':i+1,'input':text}
    try:
     # Separate TCP connections avoid previous keep-alive disconnect artefacts; never retry a measured sample.
     response=client.post('http://127.0.0.1:18765/v1/chat/completions',json=payload,headers={'Connection':'close'},timeout=120)
     row['elapsed_s']=time.perf_counter()-t0; response.raise_for_status(); body=response.json(); row['response']=body
     raw=body['choices'][0]['message'].get('content',''); row['raw']=raw
     j=json.loads(raw); tasks=j['tasks']; row['score']=score(tasks[0],i,mode) if len(tasks)==1 else {'core':False,'semantic':False,'reverse':False,'issues':['task_count']}
    except Exception as e: row.update(error=str(e),elapsed_s=time.perf_counter()-t0)
    mem=adb('shell','dumpsys meminfo '+pid); mm=re.search(r'TOTAL RSS:\s*(\d+)',mem); row['rss_KiB']=int(mm[1]) if mm else None
    rows.append(row); print(model,mode,i+1,row.get('score',{}).get('semantic'),round(row['elapsed_s'],2),flush=True)
    (R/'controlled_ab.partial.json').write_text(json.dumps(results+[{'model':model,'load_s':load,'rows':rows}],ensure_ascii=False,indent=2),encoding='utf-8')
  results.append({'model':model,'load_s':load,'rows':rows})
 finally:
  log=adb('shell','cat /data/local/tmp/semantic-eval/ab_server.log')
  (R/(model+'.ab.server.log')).write_text(log,encoding='utf-8')
  adb('shell','kill '+pid)
 (R/'controlled_ab.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')
print('DONE',flush=True)
