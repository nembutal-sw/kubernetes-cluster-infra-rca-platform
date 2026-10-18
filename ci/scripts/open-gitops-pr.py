#!/usr/bin/env python3
"""Propose exactly one Platform digest change via GitHub; never merge or deploy."""
from __future__ import annotations
import base64
import json
import os
import pathlib
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
import yaml

EXPECTED_IMAGE = '10.7.254.6:5000/rca-platform'
DIGEST_RE = re.compile(r'sha256:[0-9a-f]{64}')

class Blocked(Exception):
    pass

def replace_digest(text: str, new_digest: str) -> str:
    """Preserve all bytes except the one approved platform.image.digest value."""
    if not DIGEST_RE.fullmatch(new_digest):
        raise Blocked('invalid new digest')
    value = yaml.safe_load(text)
    try:
        image = value['platform']['image']
        old_digest = image['digest']
    except (KeyError, TypeError):
        raise Blocked('expected Platform image mapping is absent') from None
    if image.get('repository') != EXPECTED_IMAGE or not isinstance(old_digest,str) or not DIGEST_RE.fullmatch(old_digest):
        raise Blocked('unexpected existing Platform repository or digest')
    if old_digest == new_digest:
        return text
    lines = text.splitlines(keepends=True)
    platform = False
    image_block = False
    candidates = []
    for i,line in enumerate(lines):
        if re.match(r'^platform:\s*(?:#.*)?(?:\r?\n)?$',line):
            platform=True; image_block=False; continue
        if platform and re.match(r'^\S',line) and not line.startswith('#'):
            platform=False; image_block=False
        if platform and re.match(r'^  image:\s*(?:#.*)?(?:\r?\n)?$',line):
            image_block=True; continue
        if image_block and re.match(r'^  \S',line):
            image_block=False
        if image_block:
            match=re.match(r'^(    digest:\s*)([\'\"]?)(sha256:[0-9a-f]{64})\2(\s*(?:#.*)?)(\r?\n)?$',line)
            if match:
                candidates.append((i,match))
    if len(candidates)!=1 or candidates[0][1].group(3)!=old_digest:
        raise Blocked('digest is not one unambiguous four-space YAML field')
    i,m=candidates[0]
    lines[i]=m.group(1)+m.group(2)+new_digest+m.group(2)+m.group(4)+(m.group(5) or '')
    changed=''.join(lines)
    updated=yaml.safe_load(changed)
    value['platform']['image']['digest']=new_digest
    if updated!=value:
        raise Blocked('unexpected YAML change')
    return changed

class GitHub:
    def __init__(self, repository: str, token: str):
        if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+',repository) or 'REPLACE_WITH' in repository:
            raise Blocked('GitOps repository is not configured')
        if not token:
            raise Blocked('Jenkins GitOps credential is missing')
        self.base='https://api.github.com/repos/'+repository
        self.token=token
        # No redirects, environment proxy, token in argv/URL, or response body logs.
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self,*args,**kwargs): return None
        self.opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect())
    def request(self, method: str, path: str, payload=None):
        data=None if payload is None else json.dumps(payload).encode()
        req=urllib.request.Request(self.base+path,data=data,method=method,headers={
            'Authorization':'Bearer '+self.token,'Accept':'application/vnd.github+json',
            'X-GitHub-Api-Version':'2022-11-28','Content-Type':'application/json',
            'User-Agent':'rca-jenkins-gitops-review'})
        try:
            with self.opener.open(req,timeout=30) as response:
                return json.loads(response.read(2_000_000))
        except urllib.error.HTTPError as error:
            raise Blocked(f'GitHub {method} request failed with HTTP {error.code}; no automatic write retry') from None
        except (OSError,ValueError):
            raise Blocked(f'GitHub {method} outcome unknown; inspect candidate branch before retry') from None

def main() -> int:
    state=pathlib.Path(os.environ.get('CI_STATE',''))
    expected=pathlib.Path(os.environ['WORKSPACE'])/'.rca-ci'/('build-'+os.environ['BUILD_NUMBER'])
    if state!=expected:
        raise Blocked('unexpected build state directory')
    evidence=json.loads((state/'published-images.json').read_text())
    revision=os.environ.get('SOURCE_SHA','')
    if (not re.fullmatch(r'[a-f0-9]{40}',revision) or evidence.get('source_revision')!=revision
        or os.environ.get('PIPELINE_SHA')!=revision or evidence.get('pipeline_revision')!=revision):
        raise Blocked('published source revision does not match')
    digest=evidence['platform']['digest']
    if evidence['platform']['pinned_image']!=EXPECTED_IMAGE+'@'+digest:
        raise Blocked('published Platform image mismatch')
    counts=evidence.get('test_counts',{})
    for name in ('java_surefire','java_failsafe','java_total','frontend','python'):
        row=counts.get(name,{})
        if (any(type(row.get(k)) is not int or row[k]<0 for k in ('tests','passed','failures','errors','skipped'))
            or row['passed']<=0 or row['failures'] or row['errors']
            or row['tests']!=row['passed']+row['skipped']):
            raise Blocked('actual passing test counts are missing or inconsistent')
    if any(counts['java_total'][key]!=counts['java_surefire'][key]+counts['java_failsafe'][key] for key in ('tests','passed','failures','errors','skipped')):
        raise Blocked('Java aggregate test counters differ from suites')
    security=evidence.get('security',{})
    if security.get('passed') is not True or security.get('source_revision')!=revision or security.get('trivy_version')!='0.76.0':
        raise Blocked('published image security gate is not proven')
    test_line=', '.join(label+' '+str(counts[key]['passed'])+' passed / '+str(counts[key]['skipped'])+' skipped'
        for label,key in (('Java','java_total'),('Frontend','frontend'),('Python','python')))
    repo=os.environ.get('GITOPS_REPOSITORY','')
    base=os.environ.get('GITOPS_BASE_BRANCH','main')
    path=os.environ.get('GITOPS_VALUES_PATH','')
    if base!='main' or not re.fullmatch(r'[A-Za-z0-9_./-]+\.ya?ml',path) or path.startswith('/') or '..' in path.split('/'):
        raise Blocked('unexpected GitOps base branch or values path')
    api=GitHub(repo,os.environ.get('GITHUB_TOKEN',''))
    encoded_path=urllib.parse.quote(path,safe='/')
    base_ref=api.request('GET','/git/ref/heads/main')['object']['sha']
    # Read file at immutable base SHA, then branch at exactly that same SHA.
    item=api.request('GET','/contents/'+encoded_path+'?ref='+base_ref)
    if item.get('type')!='file' or item.get('encoding')!='base64':
        raise Blocked('GitOps values is not a regular file')
    text=base64.b64decode(item['content'],validate=False).decode('utf-8')
    changed=replace_digest(text,digest)
    if changed==text:
        (state/'gitops-pr.json').write_text(json.dumps({'status':'no-change','source_revision':revision,'digest':digest},indent=2)+'\n')
        print('GitOps Platform digest already matches; no PR needed')
        return 0
    branch='ci/rca-platform-'+revision[:12]+'-b'+os.environ['BUILD_NUMBER']
    api.request('POST','/git/refs',{'ref':'refs/heads/'+branch,'sha':base_ref})
    api.request('PUT','/contents/'+encoded_path,{'message':'Update RCA Platform to '+revision[:12],
        'content':base64.b64encode(changed.encode()).decode(),'sha':item['sha'],'branch':branch})
    pr=api.request('POST','/pulls',{'title':'Update RCA Platform image '+revision[:12],
        'head':branch,'base':base,'body':'Application test, isolated runtime, Agent safe mode and image security gates passed.\n\n'
        +'Actual JUnit testcase counts: '+test_line+'.\n'
        +'Trivy0.76.0: OS/library fixable HIGH/CRITICAL gate passed; SBOM and scan evidence are retained in Jenkins.\n\n'
        +'Source: '+revision+'\nPlatform digest: '+digest+'\n\n'
        +'Changes only platform.image.digest. Agent, DB, RBAC and storage are unchanged. '
        +'Review and merge manually; this CI never merges or invokes Argo CD.'})
    url=pr.get('html_url','')
    if not url.startswith('https://github.com/'+repo+'/pull/'):
        raise Blocked('unexpected pull request URL')
    (state/'gitops-pr.json').write_text(json.dumps({'status':'opened','url':url,'source_revision':revision,'digest':digest},indent=2)+'\n')
    print('GitOps review PR: '+url)
    return 0

if __name__=='__main__':
    try:
        raise SystemExit(main())
    except Blocked as error:
        print('BLOCKED: '+str(error),file=sys.stderr)
        raise SystemExit(1)
    except (KeyError,ValueError,OSError,yaml.YAMLError):
        # Do not print exceptions containing authenticated response or file content.
        print('BLOCKED: GitOps PR was not confirmed; inspect branch/job state before retry.',file=sys.stderr)
        raise SystemExit(1)
