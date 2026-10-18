#!/usr/bin/env bash
set -euo pipefail
[[ $(id -un) == jenkins-agent && $(hostname -s) == ci-agent-01 ]] || { echo "Run only as the build user on ci-agent-01" >&2; exit 1; }
set -euo pipefail
cd "$HOME"
python3 - <<'PY'
import hashlib,json,pathlib,tempfile,urllib.request,subprocess,os,shutil
assert os.getuid()==1001
root=pathlib.Path.home()/'.local/share/rca-cicd'
root.mkdir(parents=True,exist_ok=True)
wheel=root/'pip-26.2.1-py3-none-any.whl'
expected='71138adf1f4ca900cdb7d289c21b7494329f2332b6d85f0e1c42108c0384ed3e'
url='https://files.pythonhosted.org/packages/f3/6e/1736e5b4ae2b778ef2f81c47d797de9f891d4d8acb047a24ca37a60294dd/pip-26.2.1-py3-none-any.whl'
if not wheel.exists():
    data=urllib.request.urlopen(url,timeout=60).read(5_000_000)
    assert hashlib.sha256(data).hexdigest()==expected
    wheel.write_bytes(data);wheel.chmod(0o644)
assert not wheel.is_symlink() and hashlib.sha256(wheel.read_bytes()).hexdigest()==expected
with tempfile.TemporaryDirectory(prefix='pip-proof-',dir=root) as temporary:
    venv=pathlib.Path(temporary)/'venv'
    subprocess.run(['python3','-m','venv','--without-pip',str(venv)],check=True,capture_output=True)
    env=dict(os.environ,PYTHONPATH=str(wheel))
    subprocess.run([str(venv/'bin/python'),'-m','pip','install','--no-index','--no-deps',str(wheel)],env=env,check=True,capture_output=True)
    version=subprocess.check_output([str(venv/'bin/python'),'-m','pip','--version'],text=True).split()[1]
    assert version=='26.2.1'
print(json.dumps({'user':'jenkins-agent','pipWheel':str(wheel),'version':version,'sha256':expected,'isolatedBootstrap':'passed','systemPackagesChanged':False}))
PY
