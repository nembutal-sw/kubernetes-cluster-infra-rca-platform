"""Offline boundary tests: no network, credentials, Podman or infrastructure."""
import hashlib
import importlib.util
import json
import pathlib
import tempfile
import unittest

spec=importlib.util.spec_from_file_location('delivery_evidence',pathlib.Path(__file__).resolve().parents[1]/'scripts/verify-delivery-evidence.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
REV='a'*40
TAG='git-'+REV+'-b7'

class FakeRegistry:
    def __init__(self,revision=REV,user='65532:65532'):
        self.calls=[]
        self.config=json.dumps({'config':{'User':user,'Labels':{'org.opencontainers.image.revision':revision}}}).encode()
        self.config_digest=m.sha256(self.config)
        self.manifest=json.dumps({'schemaVersion':2,'mediaType':'application/vnd.oci.image.manifest.v1+json',
            'config':{'mediaType':'application/vnd.oci.image.config.v1+json','digest':self.config_digest,'size':len(self.config)},'layers':[]}).encode()
        self.digest=m.sha256(self.manifest)
        self.headers={'content-type':'application/vnd.oci.image.manifest.v1+json','docker-content-digest':self.digest}
        self.blob_headers={'docker-content-digest':self.config_digest}
    def get(self,repository,resource,accept,limit):
        self.calls.append((repository,resource,accept,limit))
        if resource.startswith('manifests/'):return self.manifest,self.headers
        if resource=='blobs/'+self.config_digest:return self.config,self.blob_headers
        raise AssertionError('unexpected registry request')

class RegistryEvidenceTests(unittest.TestCase):
    def test_valid_body_header_config_and_revision(self):
        registry=FakeRegistry();result=m.verify_image(registry,'rca-platform',TAG,registry.digest,REV)
        self.assertEqual(result['manifest_digest'],registry.digest)
        self.assertEqual(result['revision'],REV)
        self.assertEqual([x[1] for x in registry.calls],['manifests/'+TAG,'blobs/'+registry.config_digest])
    def test_tag_digest_file_mismatch_rejected(self):
        registry=FakeRegistry()
        with self.assertRaises(m.Blocked):m.verify_image(registry,'rca-platform',TAG,'sha256:'+'b'*64,REV)
    def test_manifest_header_mismatch_rejected(self):
        registry=FakeRegistry();registry.headers['docker-content-digest']='sha256:'+'b'*64
        with self.assertRaises(m.Blocked):m.verify_image(registry,'rca-platform',TAG,registry.digest,REV)
    def test_manifest_body_mutation_rejected(self):
        registry=FakeRegistry();registry.manifest+=b' '
        with self.assertRaises(m.Blocked):m.verify_image(registry,'rca-platform',TAG,registry.digest,REV)
    def test_config_blob_mutation_rejected(self):
        registry=FakeRegistry();registry.config+=b' '
        with self.assertRaises(m.Blocked):m.verify_image(registry,'rca-platform',TAG,registry.digest,REV)
    def test_config_header_mismatch_rejected(self):
        registry=FakeRegistry();registry.blob_headers['docker-content-digest']='sha256:'+'b'*64
        with self.assertRaises(m.Blocked):m.verify_image(registry,'rca-platform',TAG,registry.digest,REV)
    def test_full_revision_mismatch_rejected(self):
        registry=FakeRegistry(revision=REV[:12])
        with self.assertRaises(m.Blocked):m.verify_image(registry,'rca-platform',TAG,registry.digest,REV)
    def test_root_image_rejected(self):
        registry=FakeRegistry(user='0')
        with self.assertRaises(m.Blocked):m.verify_image(registry,'rca-platform',TAG,registry.digest,REV)
    def test_wrong_commit_tag_rejected_before_read(self):
        registry=FakeRegistry()
        with self.assertRaises(m.Blocked):m.verify_image(registry,'rca-platform','git-'+'b'*40+'-b7',registry.digest,REV)
        self.assertFalse(registry.calls)
    def test_multi_image_index_rejected(self):
        registry=FakeRegistry();registry.headers['content-type']='application/vnd.oci.image.index.v1+json'
        with self.assertRaises(m.Blocked):m.verify_image(registry,'rca-platform',TAG,registry.digest,REV)
    def test_weak_tls_context_rejected_before_network(self):
        class Weak:
            check_hostname=False
            verify_mode=0
        with self.assertRaises(m.Blocked):m.Registry('jenkins-build','fixture-memory-only',context=Weak())
    def test_fixed_registry_paths_reject_other_repositories(self):
        registry=object.__new__(m.Registry)
        with self.assertRaises(m.Blocked):registry.get('other','manifests/'+TAG,'',1000)
        with self.assertRaises(m.Blocked):registry.get('rca-platform','blobs/../../private','',1000)

class JunitEvidenceTests(unittest.TestCase):
    def evaluate(self,text):
        with tempfile.TemporaryDirectory() as directory:
            path=pathlib.Path(directory)/'report.xml';path.write_text(text)
            return m.junit_counts([path])
    def test_actual_leaf_cases_are_counted_once(self):
        result=self.evaluate('<testsuites tests="3"><testsuite tests="2" failures="0" errors="0" skipped="0"><testcase/><testcase/></testsuite><testsuite tests="1"><testcase/></testsuite></testsuites>')
        self.assertEqual(result,{'tests':3,'passed':3,'failures':0,'errors':0,'skipped':0})
    def test_nested_aggregate_suites_are_not_double_counted(self):
        result=self.evaluate('<testsuite tests="1"><testsuite tests="1"><testcase/></testsuite></testsuite>')
        self.assertEqual(result['tests'],1)
    def test_declared_count_without_real_cases_rejected(self):
        with self.assertRaises(m.Blocked):self.evaluate('<testsuite tests="622"/>')
    def test_missing_and_empty_suite_rejected(self):
        with self.assertRaises(m.Blocked):m.junit_counts([])
        with self.assertRaises(m.Blocked):self.evaluate('<testsuite tests="0"/>')
    def test_failed_case_rejected(self):
        with self.assertRaises(m.Blocked):self.evaluate('<testsuite tests="1" failures="1"><testcase><failure/></testcase></testsuite>')
    def test_error_case_rejected(self):
        with self.assertRaises(m.Blocked):self.evaluate('<testsuite tests="1" errors="1"><testcase><error/></testcase></testsuite>')
    def test_skipped_case_recorded_honestly(self):
        result=self.evaluate('<testsuite tests="2" skipped="1"><testcase/><testcase><skipped/></testcase></testsuite>')
        self.assertEqual(result['passed'],1);self.assertEqual(result['skipped'],1)
    def test_xml_entity_is_rejected(self):
        with self.assertRaises(m.Blocked):self.evaluate('<!DOCTYPE test [<!ENTITY unsafe SYSTEM "file:///private">]><testsuite tests="1"><testcase/></testsuite>')

class GatedImageTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.state=pathlib.Path(self.temp.name);(self.state/'security').mkdir()
        self.ids={'platform':'sha256:'+'b'*64,'agent':'sha256:'+'c'*64}
        summary={'passed':True,'source_revision':REV,'images':{}}
        for name,repository in m.REPOSITORIES.items():
            value={'image':m.REGISTRY+'/'+repository+':'+TAG,'source_revision':REV,'local_image_id':self.ids[name]}
            (self.state/'security'/f'{name}-input.json').write_text(json.dumps(value))
            summary['images'][name]={'input':value}
        (self.state/'security/summary.json').write_text(json.dumps(summary))
        (self.state/'agent-safe-mode-runtime.json').write_text(json.dumps({'passed':True,'mode':'safe','source_revision':REV,'local_image_id':self.ids['agent']}))
    def inspect(self,image):return self.ids['platform' if '/rca-platform:' in image else 'agent']
    def alter(self,path,key,value):
        target=self.state/path;doc=json.loads(target.read_text());doc[key]=value;target.write_text(json.dumps(doc))
    def test_exact_scanned_ids_and_runtime_match(self):
        values=m.gated_images(self.state,REV,TAG,self.inspect)
        self.assertEqual(values['agent']['local_image_id'],self.ids['agent'])
    def test_retagged_local_image_cannot_pass(self):
        with self.assertRaisesRegex(m.Blocked,'differs from scanned'):
            m.gated_images(self.state,REV,TAG,lambda image:'sha256:'+'d'*64)
    def test_agent_runtime_image_must_be_same_scanned_id(self):
        self.alter('agent-safe-mode-runtime.json','local_image_id','sha256:'+'d'*64)
        with self.assertRaisesRegex(m.Blocked,'differs from observation'):
            m.gated_images(self.state,REV,TAG,self.inspect)
    def test_failed_security_summary_cannot_pass(self):
        self.alter('security/summary.json','passed',False)
        with self.assertRaises(m.Blocked):m.gated_images(self.state,REV,TAG,self.inspect)
    def test_wrong_scanned_revision_cannot_pass(self):
        self.alter('security/platform-input.json','source_revision','d'*40)
        with self.assertRaises(m.Blocked):m.gated_images(self.state,REV,TAG,self.inspect)
    def test_optional_sha256_prefix_normalized(self):
        self.assertEqual(m.normalized_id('b'*64),'sha256:'+'b'*64)

if __name__=='__main__':unittest.main()
