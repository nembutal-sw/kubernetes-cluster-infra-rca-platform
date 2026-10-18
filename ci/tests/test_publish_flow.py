"""Execute publish.sh against isolated process doubles, without network/Podman.

The real registry verifier's byte/digest/TLS behavior is covered separately in
test_delivery_evidence.py. These tests cover shell ordering and fail propagation.
"""
import json
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT=pathlib.Path(__file__).resolve().parents[2]
BASH=shutil.which('bash')
if BASH is None and pathlib.Path('C:/Program Files/Git/bin/bash.exe').is_file():
    BASH='C:/Program Files/Git/bin/bash.exe'
REV='a'*40
PASSWORD='public-isolated-registry-fixture'

DOUBLE=r'''
import json,os,pathlib,sys
state=pathlib.Path(os.environ['CI_STATE'])
args=sys.argv[1:]
with (state/'calls.jsonl').open('a') as f:
    f.write(json.dumps(args)+'\n')
if args[0]=='podman':
    args=args[1:]
    if args[0]=='login':
        assert sys.stdin.read()==os.environ['CI_TEST_FIXTURE_PASSWORD']
        raise SystemExit(0)
    if args[:2]==['manifest','inspect']:
        if '@sha256:' in args[-1]:
            print('Treating single images as manifest lists is not implemented',file=sys.stderr)
            raise SystemExit(125)
        if os.environ.get('CI_TEST_EXISTING_TAG')=='true':raise SystemExit(0)
        print('manifest unknown',file=sys.stderr)
        raise SystemExit(1)
    if args[0]=='push':
        path=pathlib.Path(args[args.index('--digestfile')+1])
        path.write_text('sha256:'+('b' if path.name=='platform.digest' else 'c')*64)
        raise SystemExit(0)
    raise AssertionError('unexpected Podman invocation')
assert args[0].endswith('verify-delivery-evidence.py')
mode=args[1]
if mode=='tests':
    (state/'test-evidence.json').write_text(json.dumps({'counts':{'fixture':{'tests':1,'passed':1}}}))
elif mode=='gates':
    (state/'gated-images.json').write_text(json.dumps({'images':{
        name:{'local_image_id':'sha256:'+char*64} for name,char in [('platform','d'),('agent','e')]}}))
elif mode=='registry':
    assert 'REGISTRY_PASSWORD' not in os.environ,'password must reach verifier only on stdin'
    assert sys.stdin.read()==os.environ['CI_TEST_FIXTURE_PASSWORD']
    if os.environ.get('CI_TEST_REGISTRY_PROOF_FAIL')=='true':raise SystemExit(1)
    (state/'registry-verification.json').write_text(json.dumps({'images':{
        name:{'manifest_digest':(state/(name+'.digest')).read_text().strip()}
        for name in ['platform','agent']}}))
else:
    raise AssertionError('unexpected verifier invocation')
'''


@unittest.skipUnless(BASH,'Bash is required for the publish control-flow regression')
class PublishFlowTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.folder=pathlib.Path(self.temp.name)
        self.state=self.folder/'state';self.state.mkdir()
        self.helpers=self.folder/'helpers';self.helpers.mkdir()
        binaries=self.folder/'bin';binaries.mkdir()
        python=self.state/'venv/bin/python';python.parent.mkdir(parents=True)
        double=self.folder/'double.py';double.write_text(DOUBLE,encoding='utf-8')
        podman=binaries/'podman'
        podman.write_text('#!/usr/bin/env bash\nexec "$CI_TEST_PYTHON" "$CI_TEST_DOUBLE" podman "$@"\n')
        python.write_text('#!/usr/bin/env bash\nif [ "$1" = "-" ]; then exec "$CI_TEST_PYTHON" "$@"; fi\nexec "$CI_TEST_PYTHON" "$CI_TEST_DOUBLE" "$@"\n')
        for path in [podman,python]:path.chmod(0o755)
        (self.helpers/'common.sh').write_text('set -euo pipefail\nset +x\nfail() { printf "%s\\n" "$*" >&2; exit 1; }\n')
        shutil.copyfile(ROOT/'ci/scripts/publish.sh',self.helpers/'publish.sh')
        for name in ['images-gated.sha','agent-safe-mode.sha','security-gated.sha']:
            (self.state/name).write_text(REV+'\n')
        (self.state/'security').mkdir()
        (self.state/'security/summary.json').write_text(json.dumps({'passed':True,'source_revision':REV,
            'trivy_version':'0.76.0','blocking_severities':['HIGH','CRITICAL'],'ignore_unfixed':True}))
        self.env={**os.environ,'PATH':str(binaries.as_posix())+':/usr/bin:/bin',
            'CI_STATE':self.state.as_posix(),'CI_HELPERS':self.helpers.as_posix(),
            'CI_TEST_PYTHON':pathlib.Path(sys.executable).as_posix(),
            'CI_TEST_DOUBLE':double.as_posix(),'CI_TEST_FIXTURE_PASSWORD':PASSWORD,
            'REGISTRY_USERNAME':'fixture-builder','REGISTRY_PASSWORD':PASSWORD,
            'SOURCE_SHA':REV,'PIPELINE_SHA':REV,
            'PLATFORM_IMAGE':'10.7.254.6:5000/rca-platform:git-'+REV+'-b7',
            'AGENT_IMAGE':'10.7.254.6:5000/rca-node-agent:git-'+REV+'-b7'}

    def run_publish(self,**settings):
        result=subprocess.run([BASH,'--noprofile','--norc',str(self.helpers/'publish.sh')],
            env={**self.env,**settings},stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,timeout=30)
        path=self.state/'calls.jsonl'
        calls=[json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []
        return result,calls

    def test_single_oci_images_reach_strong_registry_proof_without_manifest_list_inspect(self):
        result,calls=self.run_publish()
        self.assertEqual(result.returncode,0,result.stderr)
        pushes=[c for c in calls if c[:2]==['podman','push']]
        self.assertEqual(len(pushes),2)
        self.assertTrue(all(not any('@sha256:' in value for value in c) for c in calls if c[:3]==['podman','manifest','inspect']))
        registry=[c for c in calls if c[0].endswith('verify-delivery-evidence.py') and c[1]=='registry']
        self.assertEqual(len(registry),1)
        self.assertTrue((self.state/'published-images.json').is_file())
        self.assertNotIn(PASSWORD,result.stdout+result.stderr+json.dumps(calls))

    def test_registry_proof_failure_still_blocks_published_evidence(self):
        result,calls=self.run_publish(CI_TEST_REGISTRY_PROOF_FAIL='true')
        self.assertNotEqual(result.returncode,0)
        self.assertTrue(any(c[0].endswith('verify-delivery-evidence.py') and c[1]=='registry' for c in calls))
        self.assertFalse((self.state/'published-images.json').exists())

    def test_existing_immutable_tag_still_blocks_every_push(self):
        result,calls=self.run_publish(CI_TEST_EXISTING_TAG='true')
        self.assertNotEqual(result.returncode,0)
        self.assertFalse(any(c[:2]==['podman','push'] for c in calls))
        self.assertFalse((self.state/'published-images.json').exists())


if __name__=='__main__':unittest.main()
