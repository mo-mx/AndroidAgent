import requests, pathlib, concurrent.futures, hashlib, json, tarfile
ROOT=pathlib.Path(__file__).parent
ROOT.mkdir(exist_ok=True)
s=requests.Session(); s.proxies={'https':'http://127.0.0.1:7897'}
jobs=[]
for repo in ['unsloth/Qwen3-0.6B-GGUF','unsloth/Qwen3-1.7B-GGUF','unsloth/functiongemma-270m-it-GGUF']:
 r=s.get('https://huggingface.co/api/models/'+repo+'/tree/main',timeout=60); r.raise_for_status()
 files=r.json(); choices=[x for x in files if x['path'].endswith('Q4_K_M.gguf')]
 if not choices: choices=[x for x in files if x['path'].lower().endswith('q4_0.gguf')]
 if not choices: raise RuntimeError((repo,files))
 x=choices[0]; jobs.append({'url':'https://huggingface.co/'+repo+'/resolve/main/'+x['path'],'file':x['path'],'size':x['size'],'sha256':x.get('lfs',{}).get('oid')})
jobs.append({'url':'https://github.com/ggml-org/llama.cpp/releases/download/b11960/llama-b11960-bin-android-arm64.tar.gz','file':'android.tar.gz'})
(ROOT/'manifest.json').write_text(json.dumps(jobs,indent=2))
def download(j):
 p=ROOT/j['file']
 if not p.exists() or (j.get('size') and p.stat().st_size!=j['size']):
  r=s.get(j['url'],stream=True,timeout=180); r.raise_for_status()
  with p.open('wb') as f:
   for b in r.iter_content(1024*1024): f.write(b)
 sha=hashlib.file_digest(p.open('rb'),'sha256').hexdigest()
 if j.get('sha256') and sha!=j['sha256']: raise RuntimeError('hash mismatch '+str(p))
 print(p.name,p.stat().st_size,sha,flush=True)
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool: list(pool.map(download,jobs))
with tarfile.open(ROOT/'android.tar.gz') as t: t.extractall(ROOT/'runtime',filter='data')
print('PREPARED',flush=True)
