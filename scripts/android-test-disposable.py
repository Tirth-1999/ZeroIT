#!/usr/bin/env python3
"""Host-only ZER-21 guard. Never launches tools, mutates devices, or deletes paths.

Evidence is supplied by a future separately qualified runtime adapter. Validation
establishes consistency, NOT authenticity or authorization to operate a device.
"""
import argparse
import json
import os
from pathlib import Path
import re
import stat
import sys
import uuid

PROJECT = Path(__file__).resolve().parent.parent
DEFAULT_RUNS = PROJECT / 'reference/android-test-runs'
APP = 'org.fairshare.app'
TEST_APP = APP + '.test'
TASKS = frozenset(['assembleDebug', 'assembleDebugAndroidTest', 'testDebugUnitTest', 'lintDebug'] +
                 [':app:' + s for s in ['assembleDebug', 'assembleDebugAndroidTest', 'testDebugUnitTest', 'lintDebug']] +
                 [':shared:testDebugUnitTest', ':shared:lintDebug'])
OPTIONS = frozenset(['--rerun-tasks', '--no-daemon', '--offline', '--stacktrace', '--info', '--quiet',
                     '--console=plain', '--no-parallel', '--no-build-cache', '--no-configuration-cache'])

class GuardError(ValueError):
    pass

def require(condition, message):
    if not condition:
        raise GuardError(message)

def exact_keys(value, keys, label):
    require(type(value) is dict and set(value) == set(keys), label + ': missing or unknown fields')

def positive_int(value, label, low=1, high=2**31-1):
    require(type(value) is int and low <= value <= high, label + ': invalid integer')
    return value

def digest(value):
    require(type(value) is str and re.fullmatch(r'[0-9a-f]{64}', value), 'invalid SHA-256 digest')
    return value

def strict_load(path):
    path = Path(path)
    require(not path.is_symlink() and stat.S_ISREG(path.stat().st_mode), 'regular non-symlink JSON file required')
    require(path.stat().st_size <= 1024 * 1024, 'JSON evidence exceeds size limit')
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, 'duplicate JSON key: ' + key)
            result[key] = value
        return result
    def constant(_):
        raise GuardError('non-finite JSON number')
    try:
        return json.loads(path.read_text(), object_pairs_hook=pairs, parse_constant=constant)
    except (UnicodeError, json.JSONDecodeError) as exc:
        raise GuardError('malformed JSON evidence') from exc

def canonical_directory(value):
    require(type(value) is str and value.startswith('/') and '\x00' not in value and len(value) <= 4096, 'path must be an absolute bounded path')
    require('..' not in value.split('/') and '.' not in value.split('/') and '//' not in value,
            'path traversal or non-canonical path')
    path = Path(value)
    for ancestor in [path, *path.parents]:
        require(not ancestor.is_symlink(), 'symlink path forbidden')
    require(path.is_dir(), 'directory missing')
    require(str(path) == value and path.resolve(strict=True) == path, 'path must be canonical')
    return path

def validate_manifest(m, allowed_base=DEFAULT_RUNS):
    exact_keys(m, ['schema', 'run_id', 'synthetic_only', 'run_root', 'avd_home', 'avd_dir',
                   'avd_name', 'serial', 'adb_host', 'adb_port', 'emulator_pid',
                   'app_package', 'test_package', 'apk_sha256', 'test_apk_sha256', 'tests'], 'manifest')
    require(m['schema'] == 'zeroit-disposable-android-v1' and m['synthetic_only'] is True,
            'synthetic disposable manifest required')
    require(type(m['run_id']) is str, 'run_id must be a UUID')
    try:
        require(str(uuid.UUID(m['run_id'])) == m['run_id'], 'run_id must be a canonical UUID')
    except (ValueError, AttributeError) as exc:
        raise GuardError('invalid run_id') from exc
    base = canonical_directory(str(allowed_base))
    root = canonical_directory(m['run_root'])
    require(root == base / m['run_id'], 'run root must be exact manifest-owned child')
    require(m['avd_name'] == 'zeroit-test-' + m['run_id'], 'unexpected or protected AVD name')
    require(canonical_directory(m['avd_home']) == root / 'avd', 'wrong AVD home')
    require(canonical_directory(m['avd_dir']) == root / 'avd' / (m['avd_name'] + '.avd'), 'wrong AVD storage')
    require(type(m['serial']) is str and re.fullmatch(r'emulator-[0-9]+', m['serial']), 'emulator serial required')
    port = int(m['serial'].split('-')[1])
    require(5580 <= port <= 5680 and port % 2 == 0 and m['serial'] == f'emulator-{port}', 'protected or invalid emulator port')
    require(m['adb_host'] == '127.0.0.1', 'loopback adb server required')
    positive_int(m['adb_port'], 'adb port', 5040, 5099)
    positive_int(m['emulator_pid'], 'emulator PID')
    require(m['app_package'] == APP and m['test_package'] == TEST_APP, 'unexpected package identity')
    digest(m['apk_sha256']); digest(m['test_apk_sha256'])
    require(type(m['tests']) is list and 0 < len(m['tests']) <= 500, 'explicit test IDs required')
    require(all(type(t) is str and re.fullmatch(r'org\.fairshare\.app\.[A-Za-z0-9_.$]+#[A-Za-z0-9_$]+', t)
                for t in m['tests']), 'invalid test ID')
    require(len(set(m['tests'])) == len(m['tests']), 'duplicate test ID')
    marker = root / '.zeroit-disposable.json'
    require(marker.is_file() and not marker.is_symlink(), 'missing or symlink marker')
    mark = strict_load(marker)
    exact_keys(mark, ['run_id', 'avd_name', 'synthetic_only'], 'marker')
    require(mark['run_id'] == m['run_id'] and mark['avd_name'] == m['avd_name'] and mark['synthetic_only'] is True,
            'marker identity mismatch')
    return root

def validate_target(m, o, allowed_base=DEFAULT_RUNS):
    validate_manifest(m, allowed_base)
    exact_keys(o, ['run_id', 'kind', 'serial', 'avd_name', 'avd_dir', 'adb_host', 'adb_port',
                   'emulator_pid', 'boot_completed', 'unlocked', 'reachable_serials'], 'target observation')
    require(o['kind'] == 'emulator' and o['boot_completed'] is True and o['unlocked'] is True,
            'ready unlocked emulator required')
    for key in ['run_id', 'serial', 'avd_name', 'avd_dir', 'adb_host', 'adb_port', 'emulator_pid']:
        require(type(o[key]) is type(m[key]) and o[key] == m[key], 'target mismatch: ' + key)
    require(o['reachable_serials'] == [m['serial']], 'ambiguous or protected reachable devices')
    return True

def validate_preservation(before, after):
    keys = ['run_id', 'serial', 'apk_sha256', 'test_apk_sha256',
            'app_package_present', 'test_package_present', 'default_database_files',
            'audit_sha256', 'sentinel_sha256', 'fixture_databases']
    for label, state in [('before', before), ('after', after)]:
        exact_keys(state, keys, label + ' preservation observation')
        require(state['app_package_present'] is True and state['test_package_present'] is True,
                label + ': expected package missing')
        require(type(state['default_database_files']) is dict and 'fairshare.db' in state['default_database_files'],
                'default database evidence missing')
        require(set(state['default_database_files']) <= {'fairshare.db', 'fairshare.db-wal', 'fairshare.db-shm'},
                'unexpected default database file')
        for value in state['default_database_files'].values(): digest(value)
        digest(state['audit_sha256']); digest(state['sentinel_sha256'])
        require(state['fixture_databases'] == [], label + ': leaked fixture databases')
    require(before == after, 'package, default ledger, audit or sentinel preservation failed')
    return True

def validate_instrumentation(output, expected_tests):
    require(type(output) is str and len(output) <= 16 * 1024 * 1024, 'invalid instrumentation output')
    require(expected_tests and len(expected_tests) == len(set(expected_tests)), 'invalid expected test set')
    forbidden = ['FAILURES!!!', 'INSTRUMENTATION_FAILED', 'Process crashed', 'shortMsg=',
                 'INSTRUMENTATION_ABORTED', 'INSTRUMENTATION_RESULT: stream=Error']
    require(not any(t in output for t in forbidden), 'instrumentation failure marker')
    fields, starts, passes = {}, set(), set()
    completions = []
    summary_seen = False
    for line in output.splitlines():
        if line.startswith('INSTRUMENTATION_STATUS: '):
            require(not completions, 'status after terminal completion')
            key, sep, value = line[len('INSTRUMENTATION_STATUS: '):].partition('=')
            if sep and key in {'class','test','numtests'}:
                require(key not in fields, 'duplicate status field')
                fields[key] = value
        elif line.startswith('INSTRUMENTATION_STATUS_CODE: '):
            require(not completions and not summary_seen, 'status after terminal summary/completion')
            require(set(fields) == {'class','test','numtests'}, 'incomplete test status')
            require(fields['numtests'] == str(len(expected_tests)), 'unexpected test count')
            test = fields['class'] + '#' + fields['test']
            require(test in expected_tests, 'unexpected test executed')
            code = line.split(': ',1)[1]
            if code == '1':
                require(test not in starts and test not in passes, 'duplicate test start')
                starts.add(test)
            elif code == '0':
                require(test in starts and test not in passes, 'test pass without unique start')
                passes.add(test)
            else:
                raise GuardError('failed/error/skipped test status: ' + code)
            fields = {}
        elif re.fullmatch(r'OK \(\d+ tests?\)\s*', line):
            require(not completions and not summary_seen and not fields and starts == passes == set(expected_tests), 'early or duplicate summary')
            summary_seen = True
        elif line.startswith('INSTRUMENTATION_CODE: '):
            require(not completions and summary_seen and not fields and starts == passes == set(expected_tests), 'early or duplicate completion')
            completions.append(line.split(': ',1)[1])
    require(not fields and starts == passes == set(expected_tests), 'missing or incomplete tests')
    require(completions == ['-1'], 'missing or invalid runner completion')
    matches = re.findall(r'^OK \((\d+) tests?\)\s*$', output, re.MULTILINE)
    require(matches == [str(len(expected_tests))], 'missing or invalid JUnit summary')
    return {'passed': len(passes), 'failed': 0}

def cleanup_plan(m, o, before, after, output, allowed_base=DEFAULT_RUNS):
    # Deliberately only a plan: no filesystem deletion, process termination or adb.
    validate_target(m, o, allowed_base)
    validate_preservation(before, after)
    bindings = ['run_id', 'serial', 'apk_sha256', 'test_apk_sha256']
    exact_keys(output, bindings + ['output'], 'instrumentation envelope')
    for evidence in [before, after, output]:
        require(all(type(evidence[k]) is type(m[k]) and evidence[k] == m[k] for k in bindings), 'mixed-run or wrong-build evidence')
    result = validate_instrumentation(output['output'], m['tests'])
    return {'status': 'host-evidence-consistent', 'delete_candidate': m['run_root'],
            'execution_authorized': False, 'runtime_verified': False, 'tests': result,
            'note': 'Revalidate live identity and backup/canary gates before any separately authorized runtime cleanup.'}

def validate_build_args(args, environment=None):
    environment = os.environ if environment is None else environment
    require(args and any(arg in TASKS for arg in args), 'explicit allowlisted build task required')
    require(all(arg in TASKS or arg in OPTIONS for arg in args),
            'only exact offline build/unit tasks and approved flags allowed; device tasks, aliases and custom scripts/properties refused')
    for key, value in environment.items():
        require(not (value and (key in {'GRADLE_OPTS','JAVA_OPTS','JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS'}
                                or key.startswith('ORG_GRADLE_PROJECT_'))), 'injected build environment refused: ' + key)
    return list(args)

def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    guard = sub.add_parser('guard-build')
    guard.add_argument('gradle_args', nargs=argparse.REMAINDER)
    validate = sub.add_parser('validate-evidence')
    for arg in ['manifest','target','before','after','instrumentation']:
        validate.add_argument('--' + arg, required=True, type=Path)
    args = parser.parse_args(argv)
    try:
        if args.command == 'guard-build':
            tokens = args.gradle_args
            if tokens and tokens[0] == '--': tokens = tokens[1:]
            validate_build_args(tokens)
            print('Validated exact host build/unit task allowlist; no device operation authorized.')
        else:
            m = strict_load(args.manifest)
            require(args.manifest.is_absolute() and args.manifest == args.manifest.resolve() == Path(m['run_root'])/'manifest.json', 'manifest must be in its run root')
            print(json.dumps(cleanup_plan(m, strict_load(args.target), strict_load(args.before),
                                          strict_load(args.after), strict_load(args.instrumentation)), sort_keys=True))
        return 0
    except (GuardError, OSError, TypeError, KeyError, ValueError) as exc:
        print('REFUSED: ' + str(exc), file=sys.stderr)
        return 2

if __name__ == '__main__':
    sys.exit(main())
