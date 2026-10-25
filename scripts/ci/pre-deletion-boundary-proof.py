#!/usr/bin/env python3
"""Bounded public/JPA pre-deletion evidence. Native receiving proof is a separate gate."""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
SUITES = {
    'de.caritas.cob.userservice.api.adapters.web.controller.UserAccountControllerDelegateTest': 47,
    'de.caritas.cob.userservice.api.workflow.delete.service.PublicPreDeletionJpaTest': 23,
    'de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.PreDeletionMaintenanceCodecTest': 4,
    'de.caritas.cob.userservice.api.service.user.UserAccountServiceTest': 28,
    'de.caritas.cob.userservice.api.admin.service.consultant.ConsultantAdminServiceTest': 24,
    'de.caritas.cob.userservice.api.admin.service.consultant.delete.ConsultantPreDeletionServiceTest': 4,
    'de.caritas.cob.userservice.api.admin.facade.AskerUserAdminFacadeTest': 10,
}
COMPILED = [
    'service/user/UserAccountService', 'admin/facade/AskerUserAdminFacade',
    'admin/service/consultant/ConsultantAdminService',
    'admin/service/consultant/delete/ConsultantPreDeletionService',
    'workflow/delete/service/DeletionLifecycleService',
    'workflow/delete/service/PublicPreDeletionMaintenance',
    'adapters/keycloak/lifecycle/AccountLifecycleCodec',
    'adapters/keycloak/lifecycle/PreDeletionProofSchema',
    'workflow/delete/service/PreDeletionSqlSnapshot',
    'service/chat/MembersOnlyRevocation', 'service/chat/PreDeletionChatRows',
    'admin/service/agency/ConsultantAgencyDeletionValidationService',
]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def save(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, sort_keys=True) + '\n')


def sources():
    paths = subprocess.check_output(['git', 'ls-files', '-z'], cwd=ROOT).decode().split('\0')
    return {path: sha(ROOT / path) for path in paths if path and
            (path.startswith(('src/', 'api/', 'scripts/ci/', 'tests/ci/', '.github/workflows/', '.mvn/'))
             or path in ('pom.xml', 'mvnw', 'AGENTS.md')) and (ROOT / path).is_file()}


def snapshot(destination):
    save(destination / 'before.json', {
        'head': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT).decode().strip(),
        'sourceInputs': sources(),
        'runtime': {'os': os.uname().sysname, 'architecture': os.uname().machine},
        'proof': 'PUBLIC_JPA_HTTP_DOUBLE_ONLY',
    })


def collect(destination):
    before = json.loads((destination / 'before.json').read_text())
    after = sources()
    reports = ROOT / 'target/surefire-reports'
    inventories = {}
    for source in sorted(reports.glob('TEST-*.xml')):
        suite = ET.parse(source).getroot()
        name = suite.attrib['name']
        if name not in SUITES:
            raise ValueError('Unexpected suite in fresh report directory')
        counts = {key: int(suite.get(key, '0')) for key in ('tests', 'failures', 'errors', 'skipped')}
        cases = suite.findall('testcase')
        assert len(cases) == counts['tests']
        for label, element in [('failures', 'failure'), ('errors', 'error'), ('skipped', 'skipped')]:
            assert sum(case.find(element) is not None for case in cases) == counts[label]
        assert name not in inventories
        clean = ET.Element('testsuite', name=name, **{key: str(value) for key, value in counts.items()})
        methods = []
        for case in cases:
            assert case.get('classname') == name
            entry = {'method': case.get('name'), 'outcome': 'PASS'}
            output = ET.SubElement(clean, 'testcase', classname=name, name=case.get('name'))
            for kind in ('failure', 'error', 'skipped'):
                if case.find(kind) is not None:
                    entry['outcome'] = kind.upper()
                    failure = case.find(kind)
                    trace = failure.text or ''
                    # Classes and source lines only; no exception messages, SQL, DTOs or tokens.
                    entry['exceptionClasses'] = sorted(set(re.findall(
                        r'(?:Caused by: |^)([a-zA-Z_$][a-zA-Z0-9_.$]*(?:Exception|Error))(?=:|\s|$)', trace, re.MULTILINE)))
                    entry['ownedFrames'] = list(dict.fromkeys(re.findall(
                        r'de\.caritas\.cob\.userservice\.api\.[a-zA-Z0-9_.$]+\([a-zA-Z0-9_.$]+\.java:[0-9]+\)', trace)))[:24]
                    ET.SubElement(output, kind, message='Sanitized public pre-deletion diagnostic outcome')
            methods.append(entry)
        ET.ElementTree(clean).write(destination / (name.rsplit('.', 1)[1] + '.xml'), encoding='utf-8')
        inventories[name] = {'counts': counts, 'cases': methods}
    compiled = {}
    for name in COMPILED:
        path = ROOT / 'target/classes/de/caritas/cob/userservice/api' / (name + '.class')
        if path.is_file():
            compiled[str(path.relative_to(ROOT))] = sha(path)
    proof = ROOT / 'target/test-classes/de/caritas/cob/userservice/api/workflow/delete/service/PublicPreDeletionJpaTest.class'
    if proof.is_file():
        compiled[str(proof.relative_to(ROOT))] = sha(proof)
    expected = all(name in inventories and inventories[name]['counts']['tests'] >= floor
                   for name, floor in SUITES.items())
    green = before['sourceInputs'] == after and len(compiled) == len(COMPILED) + 1 and expected and all(all(s['counts'][key] == 0 for key in ('failures', 'errors', 'skipped'))
                             for s in inventories.values())
    receipt = {
        'head': before['head'], 'sourceInputsBefore': before['sourceInputs'], 'sourceInputsAfter': after,
        'sourceStable': before['sourceInputs'] == after, 'compiled': compiled, 'suites': inventories,
        'expectedSuites': expected, 'green': green, 'proof': 'PUBLIC_JPA_HTTP_DOUBLE_ONLY',
        'nativeAcceptance': False,
    }
    save(destination / 'receipt.json', receipt)
    return receipt


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['snapshot', 'collect'])
    parser.add_argument('--destination', type=Path, required=True)
    args = parser.parse_args()
    if args.mode == 'snapshot':
        snapshot(args.destination)
    else:
        receipt = collect(args.destination)
        if not receipt['green']:
            raise SystemExit(1)


if __name__ == '__main__':
    main()
