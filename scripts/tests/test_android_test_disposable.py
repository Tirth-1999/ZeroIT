"""ZER-21 host-only safety tests. No Gradle, adb, emulator or protected data access."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import uuid

SCRIPT = Path(__file__).resolve().parents[1] / 'android-test-disposable.py'
spec = importlib.util.spec_from_file_location('disposable_guard', SCRIPT)
h = importlib.util.module_from_spec(spec)
spec.loader.exec_module(h)
HASH = 'a' * 64
TESTS = ['org.fairshare.app.ExampleTest#works', 'org.fairshare.app.ExampleTest#alsoWorks']

def transcript(tests=TESTS):
    lines = []
    for test in tests:
        cls, method = test.split('#')
        for code in [1, 0]:
            lines += [f'INSTRUMENTATION_STATUS: class={cls}', f'INSTRUMENTATION_STATUS: test={method}',
                      f'INSTRUMENTATION_STATUS: numtests={len(tests)}', f'INSTRUMENTATION_STATUS_CODE: {code}']
    return '\n'.join(lines + [f'OK ({len(tests)} tests)', 'INSTRUMENTATION_CODE: -1'])

class DisposableGuardTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='zeroit-guard-test-')
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name).resolve() / 'runs'
        self.base.mkdir()
        run_id = str(uuid.uuid4())
        self.root = self.base / run_id
        avd_name = 'zeroit-test-' + run_id
        avd = self.root/'avd'/(avd_name+'.avd')
        avd.mkdir(parents=True)
        self.m = dict(schema='zeroit-disposable-android-v1', run_id=run_id, synthetic_only=True,
                      run_root=str(self.root), avd_home=str(self.root/'avd'), avd_dir=str(avd),
                      avd_name=avd_name, serial='emulator-5580', adb_host='127.0.0.1', adb_port=5043,
                      emulator_pid=1234, app_package=h.APP, test_package=h.TEST_APP,
                      apk_sha256=HASH, test_apk_sha256=HASH, tests=TESTS.copy())
        self.marker = self.root/'.zeroit-disposable.json'
        self.marker.write_text(json.dumps(dict(run_id=run_id, avd_name=avd_name, synthetic_only=True)))
        self.o = {k:self.m[k] for k in ['run_id','serial','avd_name','avd_dir','adb_host','adb_port','emulator_pid']}
        self.o.update(kind='emulator', boot_completed=True, unlocked=True, reachable_serials=['emulator-5580'])
        self.execution = {k:self.m[k] for k in ['run_id','serial','apk_sha256','test_apk_sha256']}
        self.execution['output'] = transcript()
        self.state = dict(run_id=run_id, serial=self.m['serial'], apk_sha256=HASH, test_apk_sha256=HASH, app_package_present=True, test_package_present=True,
                          default_database_files={'fairshare.db':HASH,'fairshare.db-wal':HASH},
                          audit_sha256=HASH, sentinel_sha256=HASH, fixture_databases=[])

    def rejects(self, mutation):
        mutation()
        with self.assertRaises(h.GuardError):h.validate_target(self.m,self.o,self.base)

    def test_valid_manifest_and_observation(self):
        self.assertTrue(h.validate_target(self.m,self.o,self.base))

    def test_cleanup_returns_nonexecuting_plan_and_keeps_files(self):
        result=h.cleanup_plan(self.m,self.o,self.state,copy.deepcopy(self.state),self.execution,self.base)
        self.assertFalse(result['execution_authorized'])
        self.assertFalse(result['runtime_verified'])
        self.assertEqual(str(self.root),result['delete_candidate'])
        self.assertTrue(self.marker.exists())

    def test_schema_missing_extra_and_non_object(self):
        for change in [lambda m:m.pop('run_id'),lambda m:m.update(extra=True)]:
            m=copy.deepcopy(self.m);change(m)
            with self.assertRaises(h.GuardError):h.validate_manifest(m,self.base)
        with self.assertRaises(h.GuardError):h.validate_manifest([],self.base)

    def test_run_id_strict(self):
        for value in ['../../normal','',None,True,str(uuid.uuid4()).upper()]:
            m=copy.deepcopy(self.m);m['run_id']=value
            with self.assertRaises(h.GuardError):h.validate_manifest(m,self.base)

    def test_synthetic_flag_must_be_true_boolean(self):
        for value in [False,1,'true',None]:
            m=copy.deepcopy(self.m);m['synthetic_only']=value
            with self.assertRaises(h.GuardError):h.validate_manifest(m,self.base)

    def test_protected_physical_odd_blank_and_outside_serials(self):
        for serial in ['emulator-5556','emulator-5554','usb-phone','','emulator-5581','emulator-6000','emulator-05580']:
            m=copy.deepcopy(self.m);m['serial']=serial
            with self.assertRaises(h.GuardError):h.validate_manifest(m,self.base)

    def test_protected_avd_and_app_identity(self):
        for key,value in [('avd_name','fairshare-api35'),('avd_name','rea-splitwise-api36'),
                          ('app_package','other.app'),('test_package',h.APP)]:
            m=copy.deepcopy(self.m);m[key]=value
            with self.assertRaises(h.GuardError):h.validate_manifest(m,self.base)

    def test_invalid_ports_and_bool_pid(self):
        for key,values in [('adb_port',[True,False,'5043',5037,80,6000]),('emulator_pid',[True,0,-1,'1234'])]:
            for value in values:
                m=copy.deepcopy(self.m);m[key]=value
                with self.assertRaises(h.GuardError):h.validate_manifest(m,self.base)

    def test_remote_host_refused(self):
        self.rejects(lambda:self.m.update(adb_host='0.0.0.0'))

    def test_each_observed_identity_field_must_match(self):
        for key in ['run_id','serial','avd_name','avd_dir','adb_host','adb_port','emulator_pid']:
            o=copy.deepcopy(self.o);o[key]=None
            with self.assertRaises(h.GuardError):h.validate_target(self.m,o,self.base)

    def test_unready_locked_physical_or_ambiguous_observation(self):
        for key,value in [('kind','physical'),('boot_completed',False),('unlocked',False),
                          ('boot_completed',1),('reachable_serials',[]),
                          ('reachable_serials',['emulator-5580','emulator-5556'])]:
            o=copy.deepcopy(self.o);o[key]=value
            with self.assertRaises(h.GuardError):h.validate_target(self.m,o,self.base)

    def test_missing_stale_and_symlink_marker(self):
        self.marker.unlink()
        with self.assertRaises(h.GuardError):h.validate_manifest(self.m,self.base)
        other=self.root/'other';other.write_text('{}');self.marker.symlink_to(other)
        with self.assertRaises(h.GuardError):h.validate_manifest(self.m,self.base)

    def test_marker_true_must_not_accept_number(self):
        self.marker.write_text(json.dumps(dict(run_id=self.m['run_id'],avd_name=self.m['avd_name'],synthetic_only=1)))
        with self.assertRaises(h.GuardError):h.validate_manifest(self.m,self.base)

    def test_traversal_and_noncanonical_paths(self):
        for path in [str(self.root)+'/../'+self.root.name,str(self.root)+'/./',str(self.root)+'//', '.', '/']:
            m=copy.deepcopy(self.m);m['run_root']=path
            with self.assertRaises(h.GuardError):h.validate_manifest(m,self.base)

    def test_root_and_sibling_directory_cannot_be_cleanup_target(self):
        for path in [self.base,self.root.parent/'normal']:
            m=copy.deepcopy(self.m);m['run_root']=str(path)
            with self.assertRaises(h.GuardError):h.cleanup_plan(m,self.o,self.state,self.state,self.execution,self.base)

    def test_symlink_avd_and_ancestor(self):
        avd=Path(self.m['avd_dir']);avd.rmdir();outside=self.root/'outside';outside.mkdir();avd.symlink_to(outside)
        with self.assertRaises(h.GuardError):h.validate_manifest(self.m,self.base)
        link=self.base.parent/'link';link.symlink_to(self.base,target_is_directory=True)
        with self.assertRaises(h.GuardError):h.canonical_directory(str(link/self.root.name))

    def test_digest_and_test_allowlist_invalid(self):
        for key,value in [('apk_sha256','X'*64),('tests',[]),('tests',[TESTS[0],TESTS[0]]),
                          ('tests',['other.Class#test']),('tests',[True])]:
            m=copy.deepcopy(self.m);m[key]=value
            with self.assertRaises(h.GuardError):h.validate_manifest(m,self.base)

    def test_json_duplicate_nonfinite_and_malformed(self):
        for value in ['{"a":1,"a":2}','{"a":NaN}','{"a":Infinity}','{oops']:
            p=self.root/'bad.json';p.write_text(value)
            with self.assertRaises(h.GuardError):h.strict_load(p)

    def test_valid_result_and_single_test(self):
        self.assertEqual(2,h.validate_instrumentation(transcript(),TESTS)['passed'])
        self.assertEqual(1,h.validate_instrumentation(transcript(TESTS[:1]),TESTS[:1])['passed'])

    def test_false_green_results_refused(self):
        valid=transcript()
        bad=[ '', 'OK (0 tests)\nINSTRUMENTATION_CODE: -1',valid.replace('numtests=2','numtests=3'),
              valid.replace('STATUS_CODE: 0','STATUS_CODE: -2',1),valid.replace('STATUS_CODE: 0','STATUS_CODE: -3',1),
              valid.replace('STATUS_CODE: 0','STATUS_CODE: -1',1),valid.replace('CODE: -1','CODE: 0'),
              valid.replace('INSTRUMENTATION_CODE: -1',''),valid.replace('OK (2 tests)','OK (1 tests)'),
              valid+'\nINSTRUMENTATION_CODE: -1', valid+'\nProcess crashed.',valid+'\nINSTRUMENTATION_RESULT: shortMsg=timeout',
              valid.replace('class=org.fairshare.app.ExampleTest','class=other.Test',1),valid+valid,
              valid.replace('INSTRUMENTATION_STATUS: test=works\n','',1)]
        for output in bad:
            with self.subTest(output=output[-90:]),self.assertRaises(h.GuardError):h.validate_instrumentation(output,TESTS)

    def test_preservation_missing_package_or_changed_state_refused(self):
        for key,value in [('app_package_present',False),('test_package_present',False),('app_package_present',1),
                          ('sentinel_sha256','b'*64),('audit_sha256','b'*64),('fixture_databases',['leaked.db']),
                          ('default_database_files',{}),('default_database_files',{'fairshare.db':'b'*64})]:
            after=copy.deepcopy(self.state);after[key]=value
            with self.assertRaises(h.GuardError):h.cleanup_plan(self.m,self.o,self.state,after,self.execution,self.base)

    def test_cleanup_rechecks_marker_and_target(self):
        self.assertTrue(h.validate_target(self.m,self.o,self.base))
        self.marker.write_text('{}')
        with self.assertRaises(h.GuardError):h.cleanup_plan(self.m,self.o,self.state,self.state,self.execution,self.base)
        self.assertTrue(self.root.exists())

    def test_mixed_run_target_and_build_evidence_refused(self):
        for key,value in [('run_id',str(uuid.uuid4())),('serial','emulator-5582'),('apk_sha256','b'*64),('test_apk_sha256','b'*64)]:
            for location in ['state','output']:
                before=copy.deepcopy(self.state);after=copy.deepcopy(self.state);output=copy.deepcopy(self.execution)
                if location=='state':before[key]=value;after[key]=value
                else:output[key]=value
                with self.assertRaises(h.GuardError):h.cleanup_plan(self.m,self.o,before,after,output,self.base)

    def test_early_completion_or_summary_and_post_terminal_status_refused(self):
        valid=transcript()
        outputs=['INSTRUMENTATION_CODE: -1\n'+valid.replace('\nINSTRUMENTATION_CODE: -1',''),
                 'OK (2 tests)\n'+valid.replace('OK (2 tests)\n',''),
                 valid+'\nINSTRUMENTATION_STATUS: class=unexpected']
        for output in outputs:
            with self.assertRaises(h.GuardError):h.validate_instrumentation(output,TESTS)

    def test_symlink_json_evidence_refused(self):
        source=self.root/'source.json';source.write_text('{}')
        link=self.root/'link.json';link.symlink_to(source)
        with self.assertRaises(h.GuardError):h.strict_load(link)

    def test_host_build_allowlist(self):
        args=[':app:assembleDebug',':app:assembleDebugAndroidTest',':shared:testDebugUnitTest','--rerun-tasks']
        self.assertEqual(args,h.validate_build_args(args,{}))

    def test_task_alias_option_and_script_bypasses_refused(self):
        for arg in ['connectedDebugAndroidTest',':app:connectedDebugAndroidTest',':app:cDAT','cAT','connectedCheck',
                    'installDebug','uninstallAll','pixelApi35DebugAndroidTest','clean','test','--init-script=x','-Ix',
                    '--include-build=x','-p','-b','-c','-x','--','-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true',
                    '-Dorg.gradle.project.foo=x','--project-dir=/tmp','@args','--check-only','--dry-run']:
            with self.subTest(arg=arg),self.assertRaises(h.GuardError):h.validate_build_args(['assembleDebug',arg],{})

    def test_injected_environment_refused(self):
        for key in ['GRADLE_OPTS','JAVA_OPTS','JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS','ORG_GRADLE_PROJECT_any']:
            with self.assertRaises(h.GuardError):h.validate_build_args(['assembleDebug'],{key:'injection'})

    def test_wrapper_check_only_and_denial_never_run_gradle(self):
        env={k:v for k,v in os.environ.items() if k not in {'GRADLE_OPTS','JAVA_OPTS','JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS'} and not k.startswith('ORG_GRADLE_PROJECT_')}
        wrapper=SCRIPT.parent/'android-build.sh'
        # Safe host-only branch: never sources android-env or invokes Gradle.
        good=subprocess.run(['bash',str(wrapper),'--check-only',':app:assembleDebug'],env=env,capture_output=True,text=True)
        self.assertEqual(0,good.returncode,good.stderr)
        for arg in [':app:cDAT','uninstallAll','--init-script=/tmp/evil']:
            bad=subprocess.run(['bash',str(wrapper),'--check-only','assembleDebug',arg],env=env,capture_output=True,text=True)
            self.assertEqual(2,bad.returncode,bad.stdout+bad.stderr)
            self.assertIn('REFUSED:',bad.stderr)

if __name__=='__main__':unittest.main(verbosity=2)
