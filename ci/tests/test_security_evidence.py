import copy
import datetime as dt
import importlib.util
import json
import pathlib
import tempfile
import unittest

HERE=pathlib.Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('security_evidence', HERE.parent/'scripts/security-evidence.py')
evidence=importlib.util.module_from_spec(spec)
spec.loader.exec_module(evidence)


class EvidenceBoundaryTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.folder=pathlib.Path(self.temp.name)
        self.addCleanup(self.temp.cleanup)
        self.document={'SchemaVersion':2,'Metadata':{'OS':{'Family':'ubuntu','EOSL':False}},
                       'Results':[{'Class':'os-pkgs','Type':'ubuntu','Packages':[{'Name':'libc6'}]},
                                  {'Class':'lang-pkgs','Type':'jar','Packages':[{'Name':'spring-webmvc'}]}]}

    def save(self,name,document):
        p=self.folder/name
        p.write_text(json.dumps(document),encoding='utf-8')
        return p

    def test_valid_inventory_has_os_and_jar_coverage(self):
        evidence.report('platform',self.save('report.json',self.document),'true')

    def test_empty_results_are_not_a_pass(self):
        self.document['Results']=[]
        with self.assertRaisesRegex(AssertionError,'empty'):
            evidence.report('platform',self.save('report.json',self.document))

    def test_missing_os_analysis_is_rejected(self):
        self.document['Results']=self.document['Results'][1:]
        with self.assertRaisesRegex(AssertionError,'OS package analysis'):
            evidence.report('platform',self.save('report.json',self.document))

    def test_platform_missing_jar_analysis_is_rejected(self):
        self.document['Results']=self.document['Results'][:1]
        with self.assertRaisesRegex(AssertionError,'Java JAR analysis'):
            evidence.report('platform',self.save('report.json',self.document))

    def test_eol_base_os_is_rejected(self):
        self.document['Metadata']['OS']['EOSL']=True
        with self.assertRaisesRegex(AssertionError,'end-of-support'):
            evidence.report('platform',self.save('report.json',self.document))

    def test_empty_inventory_is_not_coverage(self):
        self.document['Results'][0]['Packages']=[]
        with self.assertRaisesRegex(AssertionError,'OS package inventory'):
            evidence.report('platform',self.save('report.json',self.document),'true')

    def test_stale_database_is_rejected(self):
        for name in ['db','java-db']:
            folder=self.folder/name
            folder.mkdir()
            meta={'UpdatedAt':(dt.datetime.now(dt.timezone.utc)-dt.timedelta(days=9)).isoformat()}
            (folder/'metadata.json').write_text(json.dumps(meta))
            (folder/('trivy.db' if name=='db' else 'trivy-java.db')).write_bytes(b'fixture')
        with self.assertRaisesRegex(AssertionError,'stale'):
            evidence.database(self.folder,self.folder/'database-evidence.json')

    def make_summary_inputs(self,exit_code,findings):
        for name in ['platform','agent']:
            self.save(f'{name}-vulnerabilities.json',self.document)
            gate=copy.deepcopy(self.document)
            gate['Results'][0]['Vulnerabilities']=findings
            self.save(f'{name}-gate.json',gate)
            (self.folder/f'{name}-gate.exit').write_text(str(exit_code))
            self.save(f'{name}-input.json',{'local_image_id':'sha256:'+'a'*64})
            self.save(f'{name}-sbom.cdx.json',{'bomFormat':'CycloneDX'})

    def test_exit_zero_with_blocking_findings_is_rejected(self):
        self.make_summary_inputs(0,[{'Severity':'CRITICAL','VulnerabilityID':'CVE-fixture'}])
        with self.assertRaisesRegex(AssertionError,'exit zero conflicts'):
            evidence.summarize(self.folder,'a'*40)
        self.assertFalse((self.folder/'summary.json').exists())

    def test_failed_scan_is_recorded_as_blocked(self):
        self.make_summary_inputs(1,[{'Severity':'HIGH','VulnerabilityID':'CVE-fixture'}])
        evidence.summarize(self.folder,'a'*40)
        self.assertFalse(json.loads((self.folder/'summary.json').read_text())['passed'])


if __name__=='__main__':
    unittest.main()
