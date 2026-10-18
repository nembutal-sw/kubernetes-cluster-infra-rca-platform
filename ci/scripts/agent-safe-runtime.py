#!/usr/bin/env python3
"""Run the image's real Agent CLI against isolated loopback-only API fixtures.

No host mounts, real API credentials, external network, source monkeypatching,
collector replacements, or kernel commands are used. Output contains summaries.
"""
import errno
import http.server
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import threading
import urllib.parse

SAFE={'node','kubernetes','dns'}
DISABLED={'systemd','kernel','disk','inode','memory','process','network',
          'conntrack','runtime','kubelet','cni'}
NODE='ci-safe-node'
MAX_BYTES=65536
# Public isolated fixtures, deliberately not usable against any real backend.
BOOTSTRAP_FIXTURE='ci-only-bootstrap-fixture'
NODE_FIXTURE='ci-only-node-fixture'
KUBE_FIXTURE='ci-only-kube-fixture'

CHILD_WRAPPER=r'''
import atexit,json,os,runpy,sys
audit={'external_commands':0,'forbidden_commands':0,'readonly_uname_probes':0,'loopback_connections':0,'blocked_connections':0}
def observe(event,args):
    if event=='subprocess.Popen':
        audit['external_commands']+=1
        # CPython3.12 platform.platform() itself resolves processor via uname -p.
        # Permit only that fixed read-only probe; no shell or general commands.
        executable,argv=args[0],args[1]
        if executable in {'uname','/usr/bin/uname','/bin/uname'} and argv==[executable,'-p']:
            audit['readonly_uname_probes']+=1
        else:
            audit['forbidden_commands']+=1
            raise RuntimeError('command forbidden by safe-mode runtime gate')
    if event=='socket.connect':
        address=args[1]
        allowed=(isinstance(address,tuple) and len(address)>=2 and
                 address[0]=='127.0.0.1' and address[1]==int(os.environ['CI_FIXTURE_PORT']))
        if not allowed:
            audit['blocked_connections']+=1
            raise RuntimeError('non-fixture connection forbidden')
        audit['loopback_connections']+=1
sys.addaudithook(observe)
atexit.register(lambda: print('CI_AGENT_AUDIT='+json.dumps(audit,sort_keys=True)))
sys.argv=['node_agent.main',*sys.argv[1:]]
runpy.run_module('node_agent.main',run_name='__main__')
'''


class Fixture(http.server.BaseHTTPRequestHandler):
    records=[]
    violations=[]
    payloads={}
    responses=[]

    def log_message(self,*args):
        pass

    def send(self,code,body):
        raw=json.dumps(body,separators=(',',':')).encode()
        self.send_response(code)
        self.send_header('Content-Type','application/json')
        self.send_header('Content-Length',str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        path=urllib.parse.urlsplit(self.path).path
        self.records.append(('GET',path))
        if self.headers.get('Authorization')!='Bearer '+KUBE_FIXTURE:
            self.violations.append('unexpected Kubernetes fixture credential')
            return self.send(401,{'message':'fixture credential required'})
        if path=='/api/v1/nodes/'+NODE:
            return self.send(200,{'metadata':{'name':NODE,'labels':{}},
                'status':{'conditions':[{'type':'Ready','status':'True'},
                    {'type':'MemoryPressure','status':'False'},
                    {'type':'DiskPressure','status':'False'},
                    {'type':'PIDPressure','status':'False'}],
                    'nodeInfo':{'containerRuntimeVersion':'fixture://0'}}})
        # Match the deployed own-node-only read permission. Broad read attempts
        # remain forbidden; graceful incomplete evidence is expected.
        return self.send(403,{'message':'fixture grants own-node GET only'})

    def do_POST(self):
        path=urllib.parse.urlsplit(self.path).path
        self.records.append(('POST',path))
        allowed={'/api/agents/register','/api/agents/heartbeat',
                 '/api/agents/evidence-requests','/api/agents/evidence-responses'}
        if path not in allowed:
            self.violations.append('unexpected POST route')
            return self.send(405,{'message':'route forbidden'})
        length=int(self.headers.get('Content-Length','0'))
        if not 0<length<2*MAX_BYTES:
            self.violations.append('unbounded fixture request')
            return self.send(413,{'message':'request exceeds fixture bound'})
        body=json.loads(self.rfile.read(length))
        expected=BOOTSTRAP_FIXTURE if path.endswith('/register') else NODE_FIXTURE
        if self.headers.get('Authorization')!='Bearer '+expected:
            self.violations.append('unexpected Agent fixture credential')
            return self.send(401,{'message':'fixture credential required'})
        self.payloads[path]=body
        if path.endswith('/register'):
            return self.send(201,{'agent_id':'ci-safe-agent','node_token':NODE_FIXTURE})
        if path.endswith('/heartbeat'):
            return self.send(200,{'status':'healthy'})
        if path.endswith('/evidence-requests'):
            return self.send(200,[{'request_id':'ci-safe-evidence',
                'requested_collectors':sorted(SAFE|DISABLED|{'ci-unsupported'})}])
        self.responses.append(body)
        return self.send(200,{'status':'completed'})

    def reject_mutation(self):
        self.records.append((self.command,urllib.parse.urlsplit(self.path).path))
        self.violations.append('mutating API request')
        self.send(405,{'message':'mutation forbidden'})

    do_PUT=reject_mutation
    do_PATCH=reject_mutation
    do_DELETE=reject_mutation


def child(args,env):
    completed=subprocess.run([sys.executable,'-c',CHILD_WRAPPER,*args],
        env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,
        timeout=35,check=False)
    assert completed.returncode==0, f'Agent CLI exited {completed.returncode}'
    assert len(completed.stdout.encode())<MAX_BYTES, 'unbounded Agent stdout'
    assert len(completed.stderr.encode())<MAX_BYTES, 'unbounded Agent stderr'
    lines=completed.stdout.splitlines()
    audits=[json.loads(x.removeprefix('CI_AGENT_AUDIT=')) for x in lines if x.startswith('CI_AGENT_AUDIT=')]
    assert len(audits)==1, 'audit report missing or duplicated'
    audit=audits[0]
    assert audit['forbidden_commands']==0, 'safe mode attempted a forbidden command'
    assert audit['blocked_connections']==0, 'Agent attempted a non-fixture connection'
    return '\n'.join(x for x in lines if not x.startswith('CI_AGENT_AUDIT=')),audit


def kernel_sandbox():
    assert os.getuid()==65532 and os.geteuid()==65532 and os.getgid()==65532
    status={}
    for line in pathlib.Path('/proc/self/status').read_text().splitlines():
        key,_,value=line.partition(':')
        status[key]=value.strip()
    for key in ['CapInh','CapPrm','CapEff','CapBnd','CapAmb']:
        assert int(status[key],16)==0, f'capability set {key} is not empty'
    assert status['NoNewPrivs']=='1'
    assert status['Seccomp']=='2', 'seccomp filter missing'
    root=[x.split() for x in pathlib.Path('/proc/mounts').read_text().splitlines() if x.split()[1]=='/']
    assert root and 'ro' in root[0][3].split(','), 'root filesystem is writable'
    try:
        fd=os.open('/app/.ci-safe-write-probe',os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    except OSError as exc:
        assert exc.errno in {errno.EROFS,errno.EACCES}, 'unexpected root write failure'
    else:
        os.close(fd)
        raise AssertionError('root filesystem unexpectedly accepted write')
    for path in ['/host','/var/run/secrets/kubernetes.io/serviceaccount/token',
                 '/var/run/docker.sock','/run/podman/podman.sock','/run/containerd/containerd.sock']:
        assert not pathlib.Path(path).exists(), 'unexpected host/API resource in test container'


def run():
    kernel_sandbox()
    assert os.environ['AGENT_MODE']=='safe' and os.environ['EBPF_ENABLED']=='false'
    assert os.environ['SYSTEMD_COLLECTOR_MODE']=='file'
    assert os.environ['KUBERNETES_TOPOLOGY_ENABLED']=='false'
    assert os.environ['AGENT_EVIDENCE_MAX_BYTES']==str(MAX_BYTES)
    server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Fixture)
    thread=threading.Thread(target=server.serve_forever,daemon=True)
    thread.start()
    try:
        with tempfile.TemporaryDirectory(prefix='rca-safe-fixture-') as temp:
            root=pathlib.Path(temp)
            paths={name:root/name for name in ['root','proc','sys','etc','var_log','run']}
            for path in paths.values(): path.mkdir()
            (paths['proc']/'sys/kernel').mkdir(parents=True)
            (paths['proc']/'sys/kernel/hostname').write_text(NODE+'\n')
            (paths['proc']/'sys/kernel/tainted').write_text('0\n')
            (paths['proc']/'uptime').write_text('1234.0 1234.0\n')
            (paths['proc']/'loadavg').write_text('0.00 0.00 0.00 1/1 1\n')
            (paths['etc']/'os-release').write_text('ID=ci-fixture\nNAME=CI Fixture\n')
            (paths['etc']/'resolv.conf').write_text('nameserver 127.0.0.1\noptions attempts:1 timeout:1\n')
            token=root/'kube-token'
            token.write_text(KUBE_FIXTURE)
            token.chmod(0o600)
            url=f'http://127.0.0.1:{server.server_port}'
            env={'PATH':os.environ.get('PATH','/usr/local/bin:/usr/bin:/bin'),
                'PYTHONDONTWRITEBYTECODE':'1','PYTHONUNBUFFERED':'1',
                'AGENT_MODE':'safe','EBPF_ENABLED':'false','SYSTEMD_COLLECTOR_MODE':'file',
                'KUBERNETES_TOPOLOGY_ENABLED':'false','NODE_NAME':NODE,
                'AGENT_EVIDENCE_MAX_BYTES':str(MAX_BYTES),'AGENT_STATE_DIR':str(root/'state'),
                'BACKEND_URL':url,'CLUSTER_ID':'ci-safe-cluster','AGENT_TOKEN':BOOTSTRAP_FIXTURE,
                'CI_FIXTURE_PORT':str(server.server_port),
                'KUBERNETES_API_URL':url,'KUBERNETES_SERVICE_HOST':'127.0.0.1',
                'KUBERNETES_SERVICE_PORT':str(server.server_port),
                'KUBERNETES_SERVICEACCOUNT_TOKEN':str(token),
                'KUBERNETES_SERVICEACCOUNT_CA':'/etc/ssl/certs/ca-certificates.crt',
                'KUBERNETES_API_MAX_ATTEMPTS':'1','KUBERNETES_API_MAX_RESPONSE_BYTES':str(MAX_BYTES)}
            env.update({'HOST_'+name.upper():str(path) for name,path in paths.items()})
            raw,cap_audit=child(['--capability-check','--output','-','--log-level','error'],env)
            capabilities=json.loads(raw)['capabilities']
            assert capabilities['mode']=='safe'
            for name in DISABLED|{'ebpf'}:
                assert capabilities['collectors'][name]['status']=='disabled', f'{name} capability enabled'
            ebpf=next(x for x in capabilities['checks'] if x['key']=='ebpf_runtime')
            assert ebpf['status']=='disabled' and ebpf['details']['ebpf_enabled'] is False
            _,cycle_audit=child(['--once','--command-timeout-seconds','1',
                '--http-timeout-seconds','2','--log-level','error'],env)
            assert not Fixture.violations, 'API fixture detected an unexpected request'
            assert len(Fixture.responses)==1, 'single evidence cycle did not complete'
            registration=Fixture.payloads['/api/agents/register']
            heartbeat=Fixture.payloads['/api/agents/heartbeat']
            for payload in [registration,heartbeat]:
                assert set(payload['supported_collectors'])==SAFE
            assert registration['metadata']['agent_mode']=='safe'
            metadata=registration['metadata']['collectors']
            assert {x['name'] for x in metadata if x['enabled_by_default']}==SAFE
            assert all(x['risk_level']=='read_only' and x['requires_privileged'] is False for x in metadata)
            assert heartbeat['health']['mode']=='safe' and heartbeat['health']['ebpf']=='disabled'
            response=Fixture.responses[0]
            assert response['request_id']=='ci-safe-evidence' and response['status']=='completed'
            collectors=response['collectors']
            assert set(collectors)==SAFE|DISABLED|{'ci-unsupported'}
            for name in DISABLED:
                assert collectors[name]['status']=='disabled', f'{name} evidence collector executed'
            assert collectors['ci-unsupported']['status']=='unsupported'
            assert collectors['node']['agent_node_name']==NODE
            assert collectors['kubernetes']['api_available'] is True
            assert collectors['kubernetes']['node_ready'] is True
            encoded=json.dumps(collectors,ensure_ascii=False,default=str).encode()
            assert len(encoded)<=MAX_BYTES
            kube=[(method,path) for method,path in Fixture.records if not path.startswith('/api/agents/')]
            assert kube and all(method=='GET' for method,path in kube), 'Kubernetes API mutation attempted'
            assert any(path=='/api/v1/nodes/'+NODE for _,path in kube)
            assert not any(path.startswith('/api/v1/services') for _,path in kube), 'topology inventory attempted'
            report={'passed':True,'source_revision':os.environ['CI_EXPECTED_SOURCE_SHA'],
                'local_image_id':os.environ['CI_EXPECTED_IMAGE_ID'],'uid':os.getuid(),'mode':'safe',
                'sandbox':'rootless; read-only; cap-drop ALL; network none; loopback fixture only',
                'ebpf_enabled':False,'dmesg_enabled':False,'dmesg_control':'kernel collector disabled in safe mode',
                'privileged_collectors_disabled':True,'observation_only':True,
                'executed_external_commands':cap_audit['external_commands']+cycle_audit['external_commands'],
                'forbidden_external_commands':cap_audit['forbidden_commands']+cycle_audit['forbidden_commands'],
                'allowed_readonly_commands':['uname -p'],
                'readonly_uname_probes':cap_audit['readonly_uname_probes']+cycle_audit['readonly_uname_probes'],
                'non_fixture_connections':cap_audit['blocked_connections']+cycle_audit['blocked_connections'],
                'supported_collectors':sorted(SAFE),'disabled_collectors':sorted(DISABLED|{'ebpf'}),
                'backend_register_heartbeat_evidence_cycle':'passed against isolated fixture',
                'kubernetes_read_requests':len(kube),'kubernetes_mutating_requests':0,
                'fixture_denied_broad_read_requests':len(kube)-1,
                'evidence_collectors_bytes':len(encoded),'evidence_max_bytes':MAX_BYTES,
                'production_backend_or_cluster_access':'not performed',
                'fixture_only':True}
            print(json.dumps(report,indent=2,sort_keys=True))
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=3)


if __name__=='__main__':
    run()
