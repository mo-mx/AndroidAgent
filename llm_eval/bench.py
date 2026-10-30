import subprocess as sp, pathlib, requests, time, json, statistics
R=pathlib.Path(__file__).parent
ADB=str(R.parent/'tools/android-sdk/platform-tools/adb.exe')
serial='10.0.0.233:33917'
def adb(*args,timeout=120):
 p=sp.run([ADB,'-s',serial,*args],capture_output=True,timeout=timeout)
 if p.returncode: raise RuntimeError(p.stderr.decode(errors='replace'))
 return p.stdout.decode(errors='replace')
cases=[
('设置五分钟倒计时','CREATE','TIMER'),('取消五分钟倒计时','CANCEL','TIMER'),('再次取消不存在的五分钟倒计时','CANCEL','TIMER'),
('暂停三分钟计时器','PAUSE','TIMER'),('继续刚才的计时器','RESUME','TIMER'),('设置十点闹钟','CREATE','ALARM'),
('关闭十点闹钟','DISABLE','ALARM'),('关闭不存在的十一点闹钟','DISABLE','ALARM'),('查询闹钟，不要修改','QUERY','ALARM'),
('打开三星应用商店','OPEN','APP'),('打开Google Play','OPEN','APP'),('打开微信分身','OPEN','APP'),
('打开微信扫一扫','OPEN','APP_FEATURE'),('如果蓝牙已经打开，就不要修改','QUERY','BLUETOOTH'),
('取消刚才创建的五分钟倒计时','CANCEL','TIMER')]
protocol='''你是离线手机语义解析器，不执行操作。只输出JSON对象，格式{"tasks":[{"action":"...","target":"...","parameters":{},"conditions":[],"expectedState":"...","targetIdentity":null,"ambiguity":null}]}。action只允许CREATE OPEN DISABLE CANCEL DELETE QUERY PAUSE RESUME ENABLE SET。target只允许TIMER ALARM APP APP_FEATURE TORCH BLUETOOTH WIFI CAMERA。关闭闹钟=DISABLE，取消计时器=CANCEL。查询绝不修改，取消绝不创建。不存在的对象仍保留取消或关闭动作，由执行器检查存在性。时长秒数放parameters.seconds，时间放hour/minute。明确应用用parameters.appName，分身用parameters.profile，二级功能用parameters.feature。条件放conditions，歧义放ambiguity，不猜应用。上下文：刚才创建了五分钟倒计时，引用timer_last。不要解释，不要思考。'''
schema={'type':'object','properties':{'tasks':{'type':'array','items':{'type':'object','properties':{'action':{'type':'string','enum':['CREATE','OPEN','DISABLE','CANCEL','DELETE','QUERY','PAUSE','RESUME','ENABLE','SET']},'target':{'type':'string','enum':['TIMER','ALARM','APP','APP_FEATURE','TORCH','BLUETOOTH','WIFI','CAMERA']},'parameters':{'type':'object','properties':{'seconds':{'type':'integer'},'hour':{'type':'integer'},'minute':{'type':'integer'},'appName':{'type':'string'},'profile':{'type':'string'},'feature':{'type':'string'}},'additionalProperties':False},'conditions':{'type':'array','items':{'type':'string'}},'expectedState':{'type':'string'},'targetIdentity':{'type':['string','null']},'ambiguity':{'type':['string','null']}},'required':['action','target','parameters','conditions','expectedState','targetIdentity','ambiguity'],'additionalProperties':False}}},'required':['tasks'],'additionalProperties':False}
results=[]
for model in ['Qwen3-0.6B-Q4_K_M.gguf','Qwen3-1.7B-Q4_K_M.gguf','functiongemma-270m-it-Q4_K_M.gguf']:
 print('MODEL',model,flush=True)
 adb('push',str(R/model),'/data/local/tmp/semantic-eval/'+model,timeout=300)
 t0=time.perf_counter()
 cmd='cd /data/local/tmp/semantic-eval; LD_LIBRARY_PATH=. nohup ./llama-server -m '+model+' -c 4096 -t 4 -ngl 0 --host 127.0.0.1 --port 18765 --jinja > server.log 2>&1 < /dev/null & echo $!'
 pid=adb('shell',cmd).strip(); adb('forward','tcp:18765','tcp:18765')
 client=requests.Session(); client.trust_env=False
 try:
  deadline=time.monotonic()+150
  while True:
   try:
    if client.get('http://127.0.0.1:18765/health',timeout=2).status_code==200: break
   except requests.RequestException: pass
   if time.monotonic()>deadline: raise RuntimeError('load timeout')
   time.sleep(.5)
  load=time.perf_counter()-t0
  rows=[]
  for idx,(text,action,target) in enumerate(cases):
   start=time.perf_counter(); first=None; chunks=[]
   payload={'messages':[{'role':'system','content':protocol},{'role':'user','content':text+' /no_think'}],'temperature':0,'max_tokens':240,'stream':True,'chat_template_kwargs':{'enable_thinking':False}}
   if 'functiongemma' in model:
    payload['tools']=[{'type':'function','function':{'name':'parse_phone_tasks','description':protocol,'parameters':schema}}]
    payload['messages']=[{'role':'developer','content':'You are a model that can do function calling with the following functions'},{'role':'user','content':text}]
   else:
    payload['response_format']={'type':'json_schema','json_schema':{'name':'tasks','schema':schema}}
   try:
    with client.post('http://127.0.0.1:18765/v1/chat/completions',json=payload,stream=True,timeout=100) as response:
     response.raise_for_status()
     for line in response.iter_lines():
      if not line.startswith(b'data: ') or line==b'data: [DONE]': continue
      data=json.loads(line[6:]); delta=data.get('choices',[{}])[0].get('delta',{}); token=delta.get('content','')
      if delta.get('tool_calls'):
       token=''.join(t.get('function',{}).get('arguments','') for t in delta['tool_calls'])
      if token:
       if first is None: first=time.perf_counter()-start
       chunks.append(token)
    raw=''.join(chunks); parsed=None
    try: parsed=json.loads(raw[raw.index('{'):raw.rindex('}')+1])
    except Exception: pass
    tasks=parsed.get('tasks',[]) if isinstance(parsed,dict) else []
    correct=bool(tasks) and tasks[0].get('action')==action and tasks[0].get('target')==target
    serious=any(t.get('action')=='CREATE' for t in tasks) and action in ['CANCEL','DISABLE','QUERY','PAUSE']
    row={'input':text,'expectedAction':action,'expectedTarget':target,'raw':raw,'correctActionTarget':correct,'seriousReverse':serious,'ttft_s':first,'elapsed_s':time.perf_counter()-start}
   except Exception as e: row={'input':text,'error':str(e),'correctActionTarget':False,'seriousReverse':False,'elapsed_s':time.perf_counter()-start}
   rows.append(row); print(idx+1,row['correctActionTarget'],round(row['elapsed_s'],2),flush=True)
   (R/(model+'.partial.json')).write_text(json.dumps(rows,ensure_ascii=False,indent=2),encoding='utf-8')
  mem=adb('shell','dumpsys meminfo '+pid)
  result={'model':model,'pid':pid,'load_s':load,'rows':rows,'meminfo':mem,'correct':sum(x['correctActionTarget'] for x in rows),'seriousReverse':sum(x['seriousReverse'] for x in rows)}
 except Exception as e: result={'model':model,'error':str(e),'log':adb('shell','cat /data/local/tmp/semantic-eval/server.log')[-10000:]}
 finally:
  adb('shell','kill '+pid)
 results.append(result); (R/'results.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')
 print('RESULT',model,result.get('correct'),result.get('error'),flush=True)
