"""Local logic tests only: no network, credentials, subprocesses or cluster calls."""
from __future__ import annotations
import base64
from contextlib import contextmanager
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
BASE='d'*40
JOB='rca-delivery/rca-platform-delivery'
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
    @contextmanager
    def fixture(self,digest=NEW):
        with tempfile.TemporaryDirectory() as temporary:
            workspace=pathlib.Path(temporary)
            state=workspace/'.rca-ci/build-7';state.mkdir(parents=True)
            def row(number):return {'tests':number,'passed':number,'failures':0,'errors':0,'skipped':0}
            evidence={'source_revision':REV,'pipeline_revision':REV,
                'platform':{'digest':digest,'pinned_image':pr.EXPECTED_IMAGE+'@'+digest},
                'test_counts':{'java_surefire':row(377),'java_failsafe':row(2),'java_total':row(379),'frontend':row(24),'python':row(219)},
                'security':{'passed':True,'source_revision':REV,'trivy_version':'0.76.0'}}
            (state/'published-images.json').write_text(json.dumps(evidence))
            env={'WORKSPACE':str(workspace),'BUILD_NUMBER':'7','JOB_NAME':JOB,
                'CI_STATE':str(state),'SOURCE_SHA':REV,'PIPELINE_SHA':REV,
                'GITOPS_REPOSITORY':'example/gitops','GITOPS_VALUES_PATH':'environments/onprem/values-platform.yaml',
                'GITHUB_TOKEN':'test-fixture-only'}
            yield state,env,evidence

    def fake_github(self,calls,text=TEXT,base_ref=BASE):
        class FakeGitHub:
            def __init__(self,repository,token):self.repository=repository
            def request(self,method,path,payload=None):
                calls.append((method,path,payload))
                if method=='GET' and path=='/git/ref/heads/main':return {'object':{'sha':base_ref}}
                if method=='GET' and path.startswith('/contents/'):
                    return {'type':'file','encoding':'base64','content':base64.b64encode(text.encode()).decode(),'sha':'e'*40}
                if method=='POST' and path=='/pulls':return {'html_url':'https://github.com/example/gitops/pull/1'}
                return {}
        return FakeGitHub

    def test_branch_is_same_snapshot_and_only_one_file_is_written(self):
        calls=[]
        with self.fixture() as (state,env,_):
            with patch.dict(os.environ,env,clear=True),patch.object(pr,'GitHub',self.fake_github(calls)):
                self.assertEqual(pr.main(),0)
            artifact=json.loads((state/'gitops-pr.json').read_text())
            self.assertEqual(artifact['status'],'opened')
            self.assertEqual(artifact['jenkins_job'],JOB)
            self.assertEqual(artifact['jenkins_build_number'],7)
            self.assertEqual(artifact['previous_digest'],OLD)
            self.assertEqual(artifact['new_digest'],NEW)
            self.assertEqual(artifact['pinned_image'],pr.EXPECTED_IMAGE+'@'+NEW)
            self.assertEqual(artifact['gitops_base_revision'],BASE)
        writes=[x for x in calls if x[0]=='PUT']
        self.assertEqual(len(writes),1)
        self.assertIn('?ref='+BASE,calls[1][1])
        self.assertEqual(calls[2][2]['sha'],BASE)
        changed=base64.b64decode(writes[0][2]['content']).decode()
        self.assertEqual(changed,TEXT.replace('"'+OLD+'"','"'+NEW+'"'))
        self.assertFalse(any('/merge' in path for _,path,_ in calls))
        body=next(payload['body'] for method,path,payload in calls if method=='POST' and path=='/pulls')
        self.assertIn('Java 379 passed / 0 skipped',body)
        self.assertIn('Frontend 24 passed / 0 skipped',body)
        self.assertIn('Python 219 passed / 0 skipped',body)
        for expected in (REV,JOB+' #7',pr.EXPECTED_IMAGE+'@'+NEW,OLD,NEW,BASE,
                         'Deployment/rca-platform','rolling replacement','approved manual Argo CD sync'):
            with self.subTest(expected=expected):self.assertIn(expected,body)

    def test_old_digest_comes_from_immutable_values_not_build_claim(self):
        calls=[]
        snapshot_old='sha256:'+'f'*64
        with self.fixture() as (state,env,evidence):
            evidence['previous_digest']='sha256:'+'0'*64
            evidence['gitops_base_revision']='0'*40
            (state/'published-images.json').write_text(json.dumps(evidence))
            fake=self.fake_github(calls,text=TEXT.replace(OLD,snapshot_old))
            with patch.dict(os.environ,env,clear=True),patch.object(pr,'GitHub',fake):self.assertEqual(pr.main(),0)
            artifact=json.loads((state/'gitops-pr.json').read_text())
            self.assertEqual(artifact['previous_digest'],snapshot_old)
            self.assertEqual(artifact['gitops_base_revision'],BASE)
        body=next(payload['body'] for method,path,payload in calls if method=='POST' and path=='/pulls')
        self.assertIn('Previous image digest: `'+snapshot_old+'`',body)
        self.assertNotIn(evidence['previous_digest'],body)

    def test_invalid_build_identity_blocks_before_api(self):
        cases=[{'BUILD_NUMBER':value} for value in ('','0','-7','07','7\n','7/other','2147483648')]
        cases += [{'JOB_NAME':value} for value in ('','other/rca-platform-delivery',JOB+'\n',JOB+'/main')]
        for overrides in cases:
            with self.subTest(overrides=overrides),self.fixture() as (_,env,_):
                env.update(overrides)
                with patch.dict(os.environ,env,clear=True),patch.object(pr,'GitHub') as api:
                    with self.assertRaises(pr.Blocked):pr.main()
                    api.assert_not_called()

    def test_main_delivery_job_is_recorded_exactly(self):
        calls=[]
        with self.fixture() as (state,env,_):
            env['JOB_NAME']='rca-delivery/rca-platform-main-delivery'
            with patch.dict(os.environ,env,clear=True),patch.object(pr,'GitHub',self.fake_github(calls)):
                self.assertEqual(pr.main(),0)
            self.assertEqual(json.loads((state/'gitops-pr.json').read_text())['jenkins_job'],env['JOB_NAME'])

    def test_invalid_immutable_base_blocks_before_contents_or_writes(self):
        calls=[]
        with self.fixture() as (state,env,_):
            with patch.dict(os.environ,env,clear=True),patch.object(pr,'GitHub',self.fake_github(calls,base_ref='main')):
                with self.assertRaises(pr.Blocked):pr.main()
            self.assertFalse((state/'gitops-pr.json').exists())
        self.assertEqual([(m,p) for m,p,_ in calls],[('GET','/git/ref/heads/main')])

    def test_no_change_records_provenance_without_writes(self):
        calls=[]
        with self.fixture(digest=OLD) as (state,env,_):
            with patch.dict(os.environ,env,clear=True),patch.object(pr,'GitHub',self.fake_github(calls)):
                self.assertEqual(pr.main(),0)
            artifact=json.loads((state/'gitops-pr.json').read_text())
            self.assertEqual(artifact['status'],'no-change')
            self.assertEqual(artifact['previous_digest'],OLD)
            self.assertEqual(artifact['new_digest'],OLD)
            self.assertEqual(artifact['gitops_base_revision'],BASE)
            self.assertEqual(artifact['jenkins_build_number'],7)
        self.assertTrue(all(method=='GET' for method,_,_ in calls))
    def test_placeholder_repository_rejected(self):
        with self.assertRaises(pr.Blocked):pr.GitHub('REPLACE_WITH_OWNER/repo','fixture')
    def test_pipeline_source_mismatch_blocks_before_api(self):
        with tempfile.TemporaryDirectory() as temporary:
            workspace=pathlib.Path(temporary);state=workspace/'.rca-ci/build-7';state.mkdir(parents=True)
            (state/'published-images.json').write_text(json.dumps({'source_revision':REV,'pipeline_revision':'d'*40,'platform':{'digest':NEW,'pinned_image':pr.EXPECTED_IMAGE+'@'+NEW}}))
            env={'WORKSPACE':str(workspace),'BUILD_NUMBER':'7','JOB_NAME':JOB,'CI_STATE':str(state),'SOURCE_SHA':REV,'PIPELINE_SHA':REV}
            with patch.dict(os.environ,env,clear=True),patch.object(pr,'GitHub') as api:
                with self.assertRaises(pr.Blocked):pr.main()
                api.assert_not_called()

if __name__=='__main__':unittest.main()
