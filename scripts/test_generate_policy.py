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


class LegacyNumbersTest(unittest.TestCase):
    def test_legacy_numbers_are_left_to_the_group_blocks_unless_kept(self):
        channels = [{'name': 'Local 5 News', 'number': '5', 'group': 'Local', 'sources': [{'provider': 'masq', 'tvgId': 'l5', 'channelName': 'Local 5 News'}]}]
        offered = {'masq/tvg-id:l5'}
        self.assertNotIn('number', generate_policy.legacy_stations(channels, {}, {}, offered)['local5news'])
        self.assertEqual('5', generate_policy.legacy_stations(channels, {}, {}, offered, keep_numbers=True)['local5news']['number'])


class StarterGroupsTest(unittest.TestCase):
    def test_the_local_pattern_comes_first_and_weak_keys_are_lowercased(self):
        starter = {'groupMap': {' TV ': 'Entertainment'}, 'weak': ['TV'], 'patterns': [{'name': 'kids', 'group': 'Kids & Family'}]}
        self.assertEqual({
            'map': {'tv': 'Entertainment'}, 'weak': ['tv'], 'default': 'Entertainment',
            'patterns': [{'name': 'des moines', 'group': 'Local'}, {'name': 'kids', 'group': 'Kids & Family'}],
        }, generate_policy.starter_groups(starter, 'des moines'))

    def test_every_group_the_starter_names_has_a_block(self):
        with open(os.path.join(ROOT, 'deploy/curation-starter.json'), encoding='utf-8') as handle:
            starter = json.load(handle)
        named = set(starter['groupMap'].values()) | {p['group'] for p in starter['patterns']} | {'Local'}
        self.assertEqual(set(), named - set(generate_policy.GROUP_BLOCKS))
        self.assertEqual(set(), set(starter['weak']) - set(starter['groupMap']) - {'us'}, 'a weak bucket still maps, except a region')


class GenerateTest(unittest.TestCase):
    def test_offline_sample(self):
        with tempfile.TemporaryDirectory() as directory:
            out = os.path.join(directory, 'policy.json')
            subprocess.run([
                sys.executable, SCRIPT, '--offline', os.path.join(DATA, 'generate-policy-server.json'),
                '--legacy', os.path.join(DATA, 'generate-policy-legacy.json'),
                '--replacements', os.path.join(DATA, 'generate-policy-replacements.json'),
                '--starter-groups', os.path.join(DATA, 'generate-policy-starter.json'), '--keep-legacy-numbers',
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
        self.assertEqual(2, policy['names']['version'])
        self.assertEqual({'bbcnewsnorthamerica': 'bbcnews'}, policy['names']['aliases'],
                         'native spellings only; a disabled entry and an aggregator source add nothing; '
                         'ESPN 8 and ESPN8 share a version 2 key and need no alias')
        self.assertEqual({'map': {'news': 'News', 'sports': 'Sports'}, 'default': 'Entertainment'}, policy['groups'])
        self.assertEqual({'requireVerified': True}, policy['health'])
        numbering = policy['numbering']
        self.assertEqual(400, numbering['groupSize'])
        self.assertEqual({'Local': 1, 'Local News (US)': 10, 'News': 100, 'Sports': 300, 'En Español': 600, 'Entertainment': 700,
                          'Drama': 900, 'Classic TV': 1050, 'Movies': 1200, 'Sci-Fi & Horror': 1400, 'Comedy': 1500, 'Crime': 1600,
                          'Documentary': 1700, 'Lifestyle': 1850, 'Food & Travel': 2100, 'Kids & Family': 2200, 'Anime': 2300,
                          'Music': 2350, 'Game Shows': 2500, 'Faith': 2550, 'Shopping': 2580, 'Ambiance': 2600}, numbering['groups'])
        self.assertGreaterEqual(numbering['floor'], max(numbering['groups'].values()) + numbering['groupSize'], 'the floor is clear of every block')
        self.assertTrue(numbering['followGroup'])
        self.assertEqual({
            'local5news': {'required': True, 'name': 'Local 5 News', 'number': '5', 'group': 'Local',
                           'sources': [{'provider': 'aggregator', 'tvgId': 'local5', 'name': 'Local 5'}]},
            'sportsone': {'required': True, 'name': 'Sports One', 'number': '227', 'group': 'Sports',
                          'sources': [{'provider': 'aggregator', 'tvgId': 'real1', 'name': 'Real Sports One'}]},
            'sportstwo': {'required': True, 'name': 'The Sports Two', 'number': '228', 'group': 'Sports',
                          'sources': [{'provider': 'aggregator', 'tvgId': 'real2', 'name': 'Real Sports Two'}]},
        }, policy['stations'], 'legacy stations keep their exported numbers as overrides')


if __name__ == '__main__':
    unittest.main()
