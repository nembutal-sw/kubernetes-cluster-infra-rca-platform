#!/usr/bin/env bash
set -euo pipefail
[[ $(id -un) == jenkins-agent && $(hostname -s) == ci-agent-01 ]] || { echo "Run only as the build user on ci-agent-01" >&2; exit 1; }
set -euo pipefail
cd "$HOME"
python3 - <<'PY'
import hashlib,json,pathlib,tarfile,urllib.request
version='22.23.3'
name='node-v'+version+'-linux-x64'
expected='df450af89261115ef9f9e3830c3eeb2cc9213b63c720b1af623cb5dcbe2e02de'
root=pathlib.Path.home()/'.local/share/rca-cicd'
root.mkdir(parents=True,exist_ok=True)
target=root/name
assert not root.is_symlink() and not target.is_symlink()
if not target.exists():
    url='https://nodejs.org/dist/v'+version+'/'+name+'.tar.xz'
    archive=root/(name+'.tar.xz')
    data=urllib.request.urlopen(url,timeout=90).read(80_000_000)
    assert hashlib.sha256(data).hexdigest()==expected
    archive.write_bytes(data)
    with tarfile.open(archive,'r:xz') as tar:
        assert all(member.name==name or member.name.startswith(name+'/') for member in tar.getmembers())
        tar.extractall(root,filter='data')
    archive.unlink()
print(json.dumps({'node_version':version,'user_toolchain':str(target),'official_archive_sha256':expected,'system_install_or_sudo_grant':False}))
PY
"$HOME/.local/share/rca-cicd/node-v22.23.3-linux-x64/bin/node" --version
