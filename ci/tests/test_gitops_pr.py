"""Local logic tests only: no network, credentials, subprocesses or cluster calls."""
from __future__ import annotations
import base64
import importlib.util
import json
import os
import pathlib
import tempfile
import unittest
from unittest.mock import patch

MODULE=pathlib.Path(__file__).resolve().parents[1]/'scripts/open-gitops-pr.py'
spec=importlib.util.spec_from_file_location('gitops_pr',MODULE)
pr=importlib.util.module_from_spec(spec)
spec.loader.exec_module(pr)
OLD='sha256:'+'a'*64
NEW='sha256:'+'b'*64
REV='c'*40
TEXT='''fullnameOverride: rca
platform:
  image:
    repository: 10.7.254.6:5000/rca-platform
    digest: "'''+OLD+'''" # keep comment
  replicaCount: 2
agent:
  image:
    digest: '''+OLD+'''
'''

class DigestChangeTests(unittest.TestCase):
    def test_changes_only_platform_digest_and_preserves_comment(self):
        self.assertEqual(pr.replace_digest(TEXT,NEW),TEXT.replace('"'+OLD+'"','"'+NEW+'"'))
    def test_no_change_is_byte_identical(self):
        self.assertEqual(pr.replace_digest(TEXT,OLD),TEXT)
    def test_preserves_crlf(self):
        text=TEXT.replace('\n','\r\n')
        self.assertEqual(pr.replace_digest(text,NEW),text.replace('"'+OLD+'"','"'+NEW+'"'))
    def test_bad_digest_rejected(self):
        for value in ['latest','sha256:'+'g'*64,NEW+'\nfoo: bar']:
            with self.subTest(value=value),self.assertRaises(pr.Blocked):pr.replace_digest(TEXT,value)
    def test_other_repository_rejected(self):
        with self.assertRaises(pr.Blocked):pr.replace_digest(TEXT.replace('/rca-platform','/other'),NEW)
    def test_duplicate_digest_rejected(self):
        text=TEXT.replace('  replicaCount: 2','    digest: '+OLD+'\n  replicaCount: 2')
        with self.assertRaises(pr.Blocked):pr.replace_digest(text,NEW)
    def test_nonstandard_indent_rejected(self):
        with self.assertRaises(pr.Blocked):pr.replace_digest(TEXT.replace('    digest:','    digest :'),NEW)
    def test_missing_mapping_rejected(self):
        with self.assertRaises(pr.Blocked):pr.replace_digest('agent: {}\n',NEW)

class WorkflowTests(unittest.TestCase):
    def test_branch_is_same_snapshot_and_only_one_file_is_written(self):
        calls=[]
        class FakeGitHub:
            def __init__(self,repository,token):
                self.repository=repository
            def request(self,method,path,payload=None):
                calls.append((method,path,payload))
                if method=='GET' and path=='/git/ref/heads/main':return {'object':{'sha':'d'*40}}
                if method=='GET' and path.startswith('/contents/'):
                    return {'type':'file','encoding':'base64','content':base64.b64encode(TEXT.encode()).decode(),'sha':'e'*40}
                if method=='POST' and path=='/pulls':return {'html_url':'https://github.com/example/gitops/pull/1'}
                return {}
        with tempfile.TemporaryDirectory() as temporary:
            workspace=pathlib.Path(temporary)
            state=workspace/'.rca-ci/build-7';state.mkdir(parents=True)
            def row(number):return {'tests':number,'passed':number,'failures':0,'errors':0,'skipped':0}
            (state/'published-images.json').write_text(json.dumps({'source_revision':REV,'pipeline_revision':REV,
                'platform':{'digest':NEW,'pinned_image':pr.EXPECTED_IMAGE+'@'+NEW},
                'test_counts':{'java_surefire':row(377),'java_failsafe':row(2),'java_total':row(379),'frontend':row(24),'python':row(219)},
                'security':{'passed':True,'source_revision':REV,'trivy_version':'0.76.0'}}))
            env={'WORKSPACE':str(workspace),'BUILD_NUMBER':'7','CI_STATE':str(state),'SOURCE_SHA':REV,'PIPELINE_SHA':REV,
                 'GITOPS_REPOSITORY':'example/gitops','GITOPS_VALUES_PATH':'environments/onprem/values-platform.yaml',
                 'GITHUB_TOKEN':'test-fixture-only'}
            with patch.dict(os.environ,env,clear=True),patch.object(pr,'GitHub',FakeGitHub):self.assertEqual(pr.main(),0)
            self.assertEqual(json.loads((state/'gitops-pr.json').read_text())['status'],'opened')
        writes=[x for x in calls if x[0]=='PUT']
        self.assertEqual(len(writes),1)
        self.assertIn('?ref='+'d'*40,calls[1][1])
        self.assertEqual(calls[2][2]['sha'],'d'*40)
        changed=base64.b64decode(writes[0][2]['content']).decode()
        self.assertEqual(changed,TEXT.replace('"'+OLD+'"','"'+NEW+'"'))
        self.assertFalse(any('/merge' in path for _,path,_ in calls))
        body=next(payload['body'] for method,path,payload in calls if method=='POST' and path=='/pulls')
        self.assertIn('Java 379 passed / 0 skipped',body)
        self.assertIn('Frontend 24 passed / 0 skipped',body)
        self.assertIn('Python 219 passed / 0 skipped',body)
    def test_placeholder_repository_rejected(self):
        with self.assertRaises(pr.Blocked):pr.GitHub('REPLACE_WITH_OWNER/repo','fixture')
    def test_pipeline_source_mismatch_blocks_before_api(self):
        with tempfile.TemporaryDirectory() as temporary:
            workspace=pathlib.Path(temporary);state=workspace/'.rca-ci/build-7';state.mkdir(parents=True)
            (state/'published-images.json').write_text(json.dumps({'source_revision':REV,'pipeline_revision':'d'*40,'platform':{'digest':NEW,'pinned_image':pr.EXPECTED_IMAGE+'@'+NEW}}))
            env={'WORKSPACE':str(workspace),'BUILD_NUMBER':'7','CI_STATE':str(state),'SOURCE_SHA':REV,'PIPELINE_SHA':REV}
            with patch.dict(os.environ,env,clear=True),patch.object(pr,'GitHub') as api:
                with self.assertRaises(pr.Blocked):pr.main()
                api.assert_not_called()

if __name__=='__main__':unittest.main()
