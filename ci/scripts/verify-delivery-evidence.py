#!/usr/bin/env python3
"""Count real JUnit cases and verify fixed-registry manifest/config bytes.

No credentials at import; registry password is accepted on stdin only. TLS uses
normal hostname verification plus existing public containers CA certificate paths.
This verifies provenance, not image vulnerability/security policy acceptance.
"""
from __future__ import annotations
import base64
import hashlib
import json
import os
import pathlib
import re
import ssl
import subprocess
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

REGISTRY='10.7.254.6:5000'
REPOSITORIES={'platform':'rca-platform','agent':'rca-node-agent'}
MANIFEST_TYPES={'application/vnd.oci.image.manifest.v1+json','application/vnd.docker.distribution.manifest.v2+json'}
DIGEST=re.compile(r'sha256:[0-9a-f]{64}')

class Blocked(Exception):pass

def sha256(data):return 'sha256:'+hashlib.sha256(data).hexdigest()

def junit_counts(paths):
    totals=dict.fromkeys(('tests','failures','errors','skipped','passed'),0)
    paths=list(paths)
    if not paths:raise Blocked('required JUnit report files are absent')
    for path in paths:
        try:
            data=pathlib.Path(path).read_bytes()
            if b'<!DOCTYPE' in data or b'<!ENTITY' in data:raise Blocked('unexpected JUnit XML declarations')
            root=ET.fromstring(data)
        except (OSError,ET.ParseError):raise Blocked('JUnit report cannot be parsed; content suppressed') from None
        suites=[node for node in root.iter('testsuite') if not node.findall('testsuite')]
        if not suites:raise Blocked('JUnit report has no leaf test suites')
        for suite in suites:
            cases=suite.findall('testcase')
            actual={'tests':len(cases),'failures':sum(c.find('failure') is not None for c in cases),
                'errors':sum(c.find('error') is not None for c in cases),'skipped':sum(c.find('skipped') is not None for c in cases)}
            for key,value in actual.items():
                attr=suite.get(key)
                if attr is not None and (not re.fullmatch(r'[0-9]+',attr) or int(attr)!=value):
                    raise Blocked('JUnit counters do not match actual testcase elements')
                totals[key]+=value
    if totals['tests']==0:raise Blocked('required test suite contains zero actual cases')
    if totals['failures'] or totals['errors']:raise Blocked('JUnit reports contain failed/error cases')
    totals['passed']=totals['tests']-totals['failures']-totals['errors']-totals['skipped']
    return totals

def test_evidence(source,state):
    result={
        'java_surefire':junit_counts(sorted((source/'web-console/target/surefire-reports').glob('TEST-*.xml'))),
        'java_failsafe':junit_counts(sorted((source/'web-console/target/failsafe-reports').glob('TEST-*.xml'))),
        'frontend':junit_counts([state/'frontend-junit.xml']),
        'python':junit_counts([state/'python-junit.xml'])}
    result['java_total']={key:result['java_surefire'][key]+result['java_failsafe'][key] for key in result['java_surefire']}
    return result

def normalized_id(value):
    if isinstance(value,str) and re.fullmatch(r'[0-9a-f]{64}',value):value='sha256:'+value
    if not isinstance(value,str) or not DIGEST.fullmatch(value):raise Blocked('gated local image ID is invalid')
    return value

def gated_images(state,revision,tag,inspect):
    summary=json.loads((state/'security/summary.json').read_text())
    if summary.get('source_revision')!=revision or summary.get('passed') is not True:
        raise Blocked('security summary does not prove this revision passed')
    safe=json.loads((state/'agent-safe-mode-runtime.json').read_text())
    if safe.get('source_revision')!=revision or safe.get('passed') is not True or safe.get('mode')!='safe':
        raise Blocked('Agent observation runtime evidence does not match revision')
    values={}
    for name,repository in REPOSITORIES.items():
        value=json.loads((state/'security'/f'{name}-input.json').read_text())
        image=REGISTRY+'/'+repository+':'+tag
        if value.get('source_revision')!=revision or value.get('image')!=image:
            raise Blocked('scanned image identity does not match this build')
        image_id=normalized_id(value.get('local_image_id'))
        if normalized_id(inspect(image))!=image_id:
            raise Blocked('current local image differs from scanned image')
        recorded=summary.get('images',{}).get(name,{}).get('input',{})
        if recorded!=value:raise Blocked('scan summary and image input evidence differ')
        if name=='agent' and normalized_id(safe.get('local_image_id'))!=image_id:
            raise Blocked('scanned Agent differs from observation runtime image')
        values[name]={'image':image,'local_image_id':image_id}
    return values

def inspect_local_image(image):
    result=subprocess.run(['podman','image','inspect','--format','{{.Id}}',image],
        stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,timeout=30,check=False)
    if result.returncode:raise Blocked('could not inspect local gated image; details suppressed')
    return result.stdout.strip()

def existing_registry_context():
    context=ssl.create_default_context()
    home=pathlib.Path.home()
    candidates=[home/'.config/containers/certs.d'/REGISTRY/'ca.crt',
        pathlib.Path('/etc/containers/certs.d')/REGISTRY/'ca.crt',
        pathlib.Path('/etc/docker/certs.d')/REGISTRY/'ca.crt']
    for path in candidates:
        if path.is_file() and os.access(path,os.R_OK):
            try:context.load_verify_locations(cafile=str(path))
            except (OSError,ssl.SSLError):raise Blocked('existing public registry CA could not be loaded') from None
    if not context.check_hostname or context.verify_mode!=ssl.CERT_REQUIRED:
        raise Blocked('TLS certificate/hostname verification must remain enabled')
    return context

class Registry:
    def __init__(self,username,password,context=None):
        if not re.fullmatch(r'[A-Za-z0-9._-]{1,64}',username) or not password or len(password)>4096:
            raise Blocked('registry build authentication input is invalid')
        self.auth='Basic '+base64.b64encode((username+':'+password).encode()).decode()
        context=context or existing_registry_context()
        if not context.check_hostname or context.verify_mode!=ssl.CERT_REQUIRED:
            raise Blocked('TLS certificate/hostname verification must remain enabled')
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self,*args,**kwargs):return None
        self.opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect(),
            urllib.request.HTTPSHandler(context=context))
    def get(self,repository,resource,accept,limit):
        if repository not in REPOSITORIES.values():raise Blocked('registry repository is not permitted')
        if not re.fullmatch(r'(?:manifests/git-[a-f0-9]{40}-b[0-9]{1,12}|blobs/sha256:[a-f0-9]{64})',resource):
            raise Blocked('registry resource path is not permitted')
        request=urllib.request.Request('https://'+REGISTRY+'/v2/'+repository+'/'+resource,
            headers={'Authorization':self.auth,'Accept':accept,'Accept-Encoding':'identity','User-Agent':'rca-ci-delivery-verifier'})
        try:
            with self.opener.open(request,timeout=30) as response:
                if response.status!=200 or response.headers.get('Content-Encoding','identity').lower()!='identity':
                    raise Blocked('unexpected registry response status/encoding')
                body=response.read(limit+1)
                if len(body)>limit:raise Blocked('registry response exceeds expected bound')
                return body,{k.lower():v for k,v in response.headers.items()}
        except Blocked:raise
        except urllib.error.HTTPError as error:
            raise Blocked('registry HTTPS read failed with HTTP '+str(error.code)+'; authentication/body suppressed') from None
        except Exception:
            raise Blocked('registry HTTPS/TLS read failed; authentication and details suppressed') from None

def verify_image(registry,repository,tag,expected_digest,revision):
    if not re.fullmatch(r'[a-f0-9]{40}',revision) or not DIGEST.fullmatch(expected_digest):
        raise Blocked('expected source revision/image digest is invalid')
    if not re.fullmatch(r'git-'+revision+r'-b[0-9]{1,12}',tag):raise Blocked('image tag does not match full source revision')
    body,headers=registry.get(repository,'manifests/'+tag,', '.join(sorted(MANIFEST_TYPES)),1_000_000)
    media_type=headers.get('content-type','').split(';',1)[0].strip()
    if media_type not in MANIFEST_TYPES:raise Blocked('single-image manifest content type is required')
    manifest_digest=sha256(body)
    if manifest_digest!=expected_digest or headers.get('docker-content-digest')!=expected_digest:
        raise Blocked('tag manifest bytes/header differ from pushed digest file')
    try:manifest=json.loads(body)
    except ValueError:raise Blocked('manifest JSON is invalid; content suppressed') from None
    if manifest.get('schemaVersion')!=2 or manifest.get('mediaType')!=media_type:
        raise Blocked('manifest schema/media type is unexpected')
    config=manifest.get('config',{})
    config_digest=config.get('digest','');size=config.get('size')
    if not isinstance(config_digest,str) or not DIGEST.fullmatch(config_digest) or isinstance(size,bool) or not isinstance(size,int) or not 0<size<=10_000_000:
        raise Blocked('manifest config descriptor is invalid')
    blob,blob_headers=registry.get(repository,'blobs/'+config_digest,'application/octet-stream',10_000_000)
    if len(blob)!=size or sha256(blob)!=config_digest:
        raise Blocked('config blob bytes do not match manifest size/digest')
    if blob_headers.get('docker-content-digest',config_digest)!=config_digest:
        raise Blocked('config blob digest header differs')
    try:value=json.loads(blob)
    except ValueError:raise Blocked('image config JSON is invalid; content suppressed') from None
    config=value.get('config',{})
    if config.get('Labels',{}).get('org.opencontainers.image.revision')!=revision:
        raise Blocked('remote OCI revision label differs from full source SHA')
    if config.get('User')!='65532:65532':raise Blocked('remote image user differs from non-root runtime gate')
    return {'image':REGISTRY+'/'+repository+':'+tag,'manifest_digest':manifest_digest,
            'config_digest':config_digest,'revision':revision,'user':'65532:65532','tls_verified':True}

def main():
    if sys.argv[1:] not in (['tests'],['gates'],['registry']):raise Blocked('mode must be tests, gates or registry')
    state=pathlib.Path(os.environ['CI_STATE']);source=pathlib.Path(os.environ['CI_SOURCE'])
    revision=os.environ['SOURCE_SHA'];build=os.environ['BUILD_NUMBER']
    if (not re.fullmatch(r'[a-f0-9]{40}',revision) or os.environ.get('PIPELINE_SHA')!=revision
        or not re.fullmatch(r'[0-9]{1,12}',build)):
        raise Blocked('build/source identity is invalid')
    if sys.argv[1]=='tests':
        value={'source_revision':revision,'pipeline_revision':revision,'counts':test_evidence(source,state)}
        (state/'test-evidence.json').write_text(json.dumps(value,indent=2)+'\n')
        print(json.dumps({'test_counts':value['counts']}))
        return
    gated=gated_images(state,revision,'git-'+revision+'-b'+build,inspect_local_image)
    if sys.argv[1]=='gates':
        (state/'gated-images.json').write_text(json.dumps({'source_revision':revision,'images':gated},indent=2)+'\n')
        print('Scanned image IDs match current local tags and runtime evidence')
        return
    password=sys.stdin.read(4097)
    registry=Registry(os.environ.get('REGISTRY_USERNAME',''),password)
    values={}
    for name,repository in REPOSITORIES.items():
        expected=(state/(name+'.digest')).read_text().strip()
        values[name]=verify_image(registry,repository,'git-'+revision+'-b'+build,expected,revision)
        if values[name]['config_digest']!=gated[name]['local_image_id']:
            raise Blocked('remote image config differs from scanned local image ID')
        values[name]['scanned_local_image_id']=gated[name]['local_image_id']
    (state/'registry-verification.json').write_text(json.dumps({'source_revision':revision,'images':values},indent=2)+'\n')
    print(json.dumps({'remote_manifest_and_config_verification':'passed','source_revision':revision}))

if __name__=='__main__':
    try:main()
    except Blocked as error:print('BLOCKED: '+str(error),file=sys.stderr);raise SystemExit(1)
    except Exception:print('BLOCKED: evidence verification failed; sensitive details suppressed',file=sys.stderr);raise SystemExit(1)
