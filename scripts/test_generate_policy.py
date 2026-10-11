"""Tests for generate-policy.py: python3 -m unittest discover -s scripts -p 'test_*.py'"""
import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SCRIPT = os.path.join(HERE, 'generate-policy.py')
DATA = os.path.join(HERE, 'testdata')

spec = importlib.util.spec_from_file_location('generate_policy', SCRIPT)
generate_policy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(generate_policy)


class BaseNameTest(unittest.TestCase):
    def test_matches_the_server_over_the_golden_corpus(self):
        for version in (1, 2):
            golden = os.path.join(ROOT, 'server/src/test/resources/policy/keys-v%d.txt' % version)
            with open(golden, encoding='utf-8') as handle:
                rows = [line.rstrip('\n').split('\t') for line in handle if line.strip()]
            mismatches = [(name, key, generate_policy.key_name(name, version)) for name, key in rows
                          if generate_policy.key_name(name, version) != key]
            self.assertEqual([], mismatches[:10], 'names.version %d' % version)
            self.assertGreater(len(rows), 3000)

    def test_version_2_drops_spaces_and_keeps_near_namesakes_apart(self):
        key = lambda name: generate_policy.key_name(name, 2)
        self.assertEqual(key('Weather Spy'), key('WeatherSpy'))
        self.assertEqual(key('123 GO!'), key('123GO!'))
        for names in (('Tennis Channel', 'Tennis TV', 'Tennis+'), ('Bloomberg TV', 'Bloomberg TV+'), ('MLB', 'MLB Channel'), ('NFL Network', 'NFL Channel')):
            self.assertEqual(len(names), len({key(n) for n in names}), names)


class GenerateTest(unittest.TestCase):
    def test_offline_sample(self):
        with tempfile.TemporaryDirectory() as directory:
            out = os.path.join(directory, 'policy.json')
            subprocess.run([
                sys.executable, SCRIPT, '--offline', os.path.join(DATA, 'generate-policy-server.json'),
                '--legacy', os.path.join(DATA, 'generate-policy-legacy.json'),
                '--replacements', os.path.join(DATA, 'generate-policy-replacements.json'),
                '--starter-groups', os.path.join(DATA, 'generate-policy-starter.json'),
                '--providers', 'whale,samsung,vizio,lg,localnow', '--out', out,
            ], check=True, capture_output=True, text=True)
            with open(out, encoding='utf-8') as handle:
                text = handle.read()
        policy = json.loads(text)
        self.assertEqual(json.dumps(policy, indent=2, sort_keys=True, ensure_ascii=False) + '\n', text, 'sorted, two-space indent')
        self.assertEqual([
            {'id': 'whale'}, {'id': 'samsung'}, {'id': 'vizio', 'exclude': {'groups': ['Local channels']}},
            {'id': 'localnow', 'include': {'groups': ['My City']}},
            {'id': 'localnow', 'include': {'groups': ['More Cities']}, 'enabled': False},
            {'id': 'localnow'},
        ], policy['providers'])
        self.assertEqual({'bbc news north america': 'bbc news', 'espn 8 the ocho': 'espn8 the ocho'}, policy['names']['aliases'],
                         'native spellings only; a disabled entry and an aggregator source add nothing')
        self.assertEqual({'map': {'news': 'News', 'sports': 'Sports'}, 'default': 'Entertainment'}, policy['groups'])
        self.assertEqual({
            'local 5 news': {'required': True, 'name': 'Local 5 News', 'number': '5', 'group': 'Local',
                             'sources': [{'provider': 'aggregator', 'tvgId': 'local5', 'name': 'Local 5'}]},
            'sports one': {'required': True, 'name': 'Sports One', 'number': '227', 'group': 'Sports',
                           'sources': [{'provider': 'aggregator', 'tvgId': 'real1', 'name': 'Real Sports One'}]},
            'sports two': {'required': True, 'name': 'The Sports Two', 'number': '228', 'group': 'Sports',
                           'sources': [{'provider': 'aggregator', 'tvgId': 'real2', 'name': 'Real Sports Two'}]},
        }, policy['stations'])


if __name__ == '__main__':
    unittest.main()
