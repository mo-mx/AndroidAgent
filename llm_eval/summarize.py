import json,statistics,re,pathlib
p=pathlib.Path(__file__).parent
out=[]
for m in json.loads((p/'results.json').read_text(encoding='utf-8')):
 rows=m['rows']; valid=[]; full=[]; hazards=[]
 for i,r in enumerate(rows):
  try:
   raw=r['raw']; j=json.loads(raw[raw.index('{'):raw.rindex('}')+1]); t=j['tasks'][0]; valid.append(i)
  except Exception: continue
  ok=r['correctActionTarget']; a=t.get('action'); target=t.get('target'); par=t.get('parameters',{})
  if i in [0,1,2,14]: ok=ok and par.get('seconds')==300
  if i==3: ok=ok and par.get('seconds')==180
  if i==4: ok=ok and t.get('targetIdentity')=='timer_last'
  if i in [5,6]: ok=ok and par.get('hour')==10 and par.get('minute',0)==0
  if i==7: ok=ok and par.get('hour')==11
  if i==9: ok=ok and ('三星' in par.get('appName','') or 'galaxy' in par.get('appName','').lower())
  if i==10: ok=ok and 'google' in par.get('appName','').lower()
  if i==11: ok=ok and bool(par.get('profile'))
  if i==12: ok=ok and par.get('feature') in ['扫一扫','扫码','SCAN']
  if i==13: ok=ok and bool(t.get('conditions'))
  if ok: full.append(i)
  reasons=[]
  if i in [0,1,2,3,14] and par.get('seconds') not in ([180] if i==3 else [300]): reasons.append('wrong duration')
  if i in [1,2,6,7,8,13,14] and a in ['CREATE','DELETE','SET','ENABLE','DISABLE'] and not (i in [6,7] and a=='DISABLE'): reasons.append('unsafe action')
  if i in [6,7,8] and target!='ALARM': reasons.append('wrong target')
  if i in [9,10,11,12] and not ok: reasons.append('wrong/ambiguous application plan')
  if reasons: hazards.append({'case':i+1,'reasons':reasons})
 elapsed=[r['elapsed_s'] for r in rows if 'raw' in r]; ttft=[r['ttft_s'] for r in rows if r.get('ttft_s') is not None]
 rss=re.search(r'TOTAL RSS:\s*(\d+)',m['meminfo'])
 out.append({'model':m['model'],'n':len(rows),'valid_json':len(valid),'action_target_correct':m['correct'],'full_semantic_correct':len(full),'full_pass_cases':[i+1 for i in full],'hazardous_plans':len(hazards),'hazards':hazards,'transport_errors':sum('error' in r for r in rows),'load_s':m['load_s'],'ttft_median_s':statistics.median(ttft),'elapsed_median_s':statistics.median(elapsed),'elapsed_max_s':max(elapsed),'rss_MiB':int(rss[1])/1024 if rss else None})
(p/'summary.json').write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(out,ensure_ascii=False,indent=2))
