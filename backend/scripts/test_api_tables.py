"""Tests for api-tables.py: python3 -m unittest discover -s scripts -p 'test_*.py'"""
import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, 'api-tables.py')

spec = importlib.util.spec_from_file_location('api_tables', SCRIPT)
api_tables = importlib.util.module_from_spec(spec)
spec.loader.exec_module(api_tables)

DOCUMENT = {
    'paths': {
        '/b': {'post': {'tags': ['Two'], 'summary': 'Make a b | with a pipe'},
               'get': {'tags': ['Two'], 'summary': 'List the bs',
                       'description': 'Every b,\nnewest first.\n\nMore detail that the table leaves out.',
                       'parameters': [{'$ref': '#/components/parameters/limit'}, {'name': 'x', 'in': 'header'}]}},
        '/a/{id}': {'delete': {'tags': ['One'], 'summary': 'Delete an a'},
                    'get': {'tags': ['One', 'Two'], 'summary': 'One a',
                            'parameters': [{'name': 'id', 'in': 'path'}]}},
    },
    'components': {'parameters': {'limit': {'name': 'limit', 'in': 'query'}}},
}

PAGE = (
    'Intro stays.\n\n'
    '<!-- api-tables:start:One -->\n\nold one\n\n<!-- api-tables:end -->\n\n'
    'Between stays.\n\n'
    '<!-- api-tables:start:Two -->\n<!-- api-tables:end -->\n\n'
    'Outro stays.\n'
)

ONE = '| Method | Path | Summary |\n| --- | --- | --- |\n| GET | `/a/{id}` | One a |\n| DELETE | `/a/{id}` | Delete an a |\n'
TWO = ('| Method | Path | Summary |\n| --- | --- | --- |\n| GET | `/b?limit=` | Every b, newest first. |\n'
       '| POST | `/b` | Make a b \\| with a pipe |\n')


class RewriteTest(unittest.TestCase):
    def test_fills_each_table_by_the_first_tag_and_keeps_the_rest_of_the_page(self):
        result = api_tables.rewrite(DOCUMENT, PAGE)
        self.assertEqual(
            'Intro stays.\n\n<!-- api-tables:start:One -->\n\n' + ONE + '\n<!-- api-tables:end -->\n\n'
            'Between stays.\n\n<!-- api-tables:start:Two -->\n\n' + TWO + '\n<!-- api-tables:end -->\n\nOutro stays.\n',
            result)

    def test_rewriting_its_own_output_changes_nothing(self):
        once = api_tables.rewrite(DOCUMENT, PAGE)
        self.assertEqual(once, api_tables.rewrite(DOCUMENT, once))

    def test_a_tag_without_a_table_is_an_error(self):
        with self.assertRaisesRegex(api_tables.TablesError, 'Two'):
            api_tables.rewrite(DOCUMENT, PAGE.replace('<!-- api-tables:start:Two -->\n<!-- api-tables:end -->\n', ''))

    def test_a_table_for_an_unused_tag_is_an_error(self):
        with self.assertRaisesRegex(api_tables.TablesError, 'Three'):
            api_tables.rewrite(DOCUMENT, PAGE + '<!-- api-tables:start:Three -->\n<!-- api-tables:end -->\n')

    def test_an_operation_needs_a_tag_and_a_summary(self):
        for operation in ({'summary': 'x'}, {'tags': ['One']}, {'tags': ['One'], 'summary': ' ', 'description': 'd'}):
            document = {'paths': {'/a': {'get': operation}}}
            with self.assertRaises(api_tables.TablesError):
                api_tables.rewrite(document, PAGE)


class CheckTest(unittest.TestCase):
    def run_script(self, *args):
        return subprocess.run([sys.executable, '-I', SCRIPT, *args], capture_output=True, text=True)

    def test_check_fails_with_a_diff_on_drift_and_passes_once_rewritten(self):
        with tempfile.TemporaryDirectory() as tmp:
            document = os.path.join(tmp, 'openapi.json')
            page = os.path.join(tmp, 'api.md')
            with open(document, 'w', encoding='utf-8') as handle:
                json.dump(DOCUMENT, handle)
            with open(page, 'w', encoding='utf-8') as handle:
                handle.write(PAGE)
            drifted = self.run_script('--check', '--openapi', document, '--page', page)
            self.assertEqual(1, drifted.returncode)
            self.assertIn('+| GET | `/a/{id}` | One a |', drifted.stdout)
            with open(page, encoding='utf-8') as handle:
                self.assertEqual(PAGE, handle.read())
            self.assertEqual(0, self.run_script('--openapi', document, '--page', page).returncode)
            self.assertEqual(0, self.run_script('--check', '--openapi', document, '--page', page).returncode)
            with open(page, encoding='utf-8') as handle:
                text = handle.read()
            with open(page, 'w', encoding='utf-8') as handle:
                handle.write(text.replace('One a |', 'One a, edited by hand |'))
            self.assertEqual(1, self.run_script('--check', '--openapi', document, '--page', page).returncode)


if __name__ == '__main__':
    unittest.main()
