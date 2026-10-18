#!/usr/bin/env python3
"""Public scan evidence only. Never reads credentials or application config."""
import collections
import datetime as dt
import hashlib
import json
import pathlib
import sys


def sha(path):
    value = hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''):
            value.update(chunk)
    return value.hexdigest()


def write(path, obj):
    with pathlib.Path(path).open('x', encoding='utf-8') as f:
        json.dump(obj, f, indent=2, sort_keys=True)
        f.write('\n')


def database(cache, output):
    cache = pathlib.Path(cache)
    now = dt.datetime.now(dt.timezone.utc)
    result = {}
    for name, max_age in [('db', 48 * 3600), ('java-db', 7 * 86400)]:
        folder = cache / name
        meta_path = folder / 'metadata.json'
        db_path = folder / ('trivy.db' if name == 'db' else 'trivy-java.db')
        assert meta_path.is_file() and not meta_path.is_symlink(), 'missing/unsafe DB metadata'
        assert db_path.is_file() and not db_path.is_symlink(), 'missing/unsafe DB file'
        meta = json.loads(meta_path.read_text(encoding='utf-8'))
        updated = dt.datetime.fromisoformat(meta['UpdatedAt'].replace('Z', '+00:00'))
        age = (now - updated).total_seconds()
        assert -300 <= age <= max_age, f'{name} is stale or has a future timestamp'
        result[name] = {
            'metadata': meta,
            'metadata_sha256': sha(meta_path),
            'database_sha256': sha(db_path),
        }
    write(output, result)


def report(component, path, inventory='false'):
    doc = json.loads(pathlib.Path(path).read_text(encoding='utf-8'))
    assert doc.get('SchemaVersion') == 2, 'unexpected Trivy JSON schema'
    results = doc.get('Results')
    assert isinstance(results, list) and results, 'empty vulnerability report'
    assert any(r.get('Class') == 'os-pkgs' for r in results), 'OS package analysis missing'
    if component == 'platform':
        assert any(r.get('Class') == 'lang-pkgs' and r.get('Type') == 'jar' for r in results), 'Java JAR analysis missing'
    os_data = doc.get('Metadata', {}).get('OS', {})
    assert os_data.get('Family') in {'ubuntu', 'debian'}, 'unexpected/undetected base OS'
    assert not os_data.get('EOSL', False), 'end-of-support OS is not acceptable'
    if inventory == 'true':
        assert any(r.get('Packages') for r in results if r.get('Class') == 'os-pkgs'), 'OS package inventory missing'
        if component == 'platform':
            assert any(r.get('Packages') for r in results if r.get('Type') == 'jar'), 'Java package inventory missing'
    return doc


def summarize(folder, source):
    folder = pathlib.Path(folder)
    result = {'source_revision': source, 'trivy_version': '0.76.0',
              'blocking_severities': ['HIGH', 'CRITICAL'], 'ignore_unfixed': True,
              'scan_scope': ['os', 'library'],
              'resource_limits': {'cpu_quota': '100%', 'memory_max': '1G',
                                  'method': 'systemd user transient scope'},
              'images': {}}
    any_failed = False
    for component in ['platform', 'agent']:
        full = report(component, folder / f'{component}-vulnerabilities.json', 'true')
        gate = report(component, folder / f'{component}-gate.json')
        code = int((folder / f'{component}-gate.exit').read_text().strip())
        findings = [v for r in gate['Results'] for v in r.get('Vulnerabilities', [])]
        assert all(v.get('Severity') in {'HIGH', 'CRITICAL'} for v in findings), 'unexpected gate severity'
        assert code != 0 or not findings, 'exit zero conflicts with blocking findings'
        any_failed |= code != 0
        result['images'][component] = {
            'input': json.loads((folder / f'{component}-input.json').read_text()),
            'gate_exit_code': code, 'blocking_package_cve_matches': len(findings),
            'blocking_severity_counts': dict(collections.Counter(v['Severity'] for v in findings)),
            'reported_package_cve_matches': sum(len(r.get('Vulnerabilities', [])) for r in full['Results']),
            'vulnerability_report_sha256': sha(folder / f'{component}-vulnerabilities.json'),
            'gate_report_sha256': sha(folder / f'{component}-gate.json'),
            'sbom_sha256': sha(folder / f'{component}-sbom.cdx.json'),
        }
    result['passed'] = not any_failed
    write(folder / 'summary.json', result)
    print('Image security gate: ' + ('PASSED' if not any_failed else 'BLOCKED'))


def main():
    mode, *args = sys.argv[1:]
    if mode == 'database':
        database(*args)
    elif mode == 'report':
        report(*args)
    elif mode == 'image':
        archive, image, image_id, source, output = args
        write(output, {'image': image, 'local_image_id': image_id,
                       'source_revision': source, 'image_archive_format': 'docker-archive',
                       'image_archive_sha256': sha(pathlib.Path(archive))})
    elif mode == 'summarize':
        summarize(*args)
    else:
        raise ValueError('invalid mode')


if __name__ == '__main__':
    main()
