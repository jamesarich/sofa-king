"""Schedule conversion and last-good publication regression tests."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location('refresh_schedules', Path(__file__).with_name('refresh-schedules.py'))
schedules = importlib.util.module_from_spec(spec)
spec.loader.exec_module(schedules)


class ScheduleTests(unittest.TestCase):
    def test_wurl_series_title_episode_and_null_collections(self):
        source = {
            'channelData': {'id': 'channel', 'name': 'Example'},
            'movies': None, 'tvSpecials': None,
            'series': [{'title': {'value': 'Series & Friends'}, 'seasons': [{'episodes': [
                {'id': 'episode', 'title': {'value': 'A < B'}, 'description': None}
            ]}]}],
            'schedules': [{'id': 'episode', 'startDateTime': '2099-01-01T23:30:00Z', 'durSecs': 3600}]
        }
        root = schedules.wurl(json.dumps(source))
        program = ET.fromstring(ET.tostring(root)).find('programme')
        self.assertEqual(program.findtext('title'), 'Series & Friends')
        self.assertEqual(program.findtext('sub-title'), 'A < B')
        self.assertEqual(program.get('stop'), '20990102003000 +0000')

    def test_ctvcraft_ends_each_item_after_its_duration_and_drops_ended_ones(self):
        source = (
            b'<channels><channel id="c1"><title>Example</title><items>'
            b'<item><title>Over</title><duration>60</duration><startTime>2000-01-01T00:00:00+00:00</startTime></item>'
            b'<item><title>A &amp; B</title><description>Desc</description><genre>horror</genre>'
            b'<duration>5400</duration><startTime>2099-01-01T01:00:00+02:00</startTime></item>'
            b'</items></channel></channels>'
        )
        root = schedules.ctvcraft(source)
        self.assertEqual(root.find('channel/display-name').text, 'Example')
        [program] = root.findall('programme')
        self.assertEqual((program.get('channel'), program.get('start'), program.get('stop')), ('c1', '20981231230000 +0000', '20990101003000 +0000'))
        self.assertEqual((program.findtext('title'), program.findtext('desc'), program.findtext('category')), ('A & B', 'Desc', 'horror'))

    def test_ctvcraft_rejects_a_start_without_timezone(self):
        source = b'<channels><channel id="c1"><title>X</title><items><item><title>T</title><duration>60</duration><startTime>2099-01-01T00:00:00</startTime></item></items></channel></channels>'
        with self.assertRaises(ValueError):
            schedules.ctvcraft(source)

    def test_frequency_only_repairs_explicit_zero_offset(self):
        root = schedules.xmltv(b'<tv><programme start="20990101000000 0000" stop="20990101010000 -0500"/></tv>', 'frequency')
        self.assertEqual(root[0].get('start'), '20990101000000 +0000')
        self.assertEqual(root[0].get('stop'), '20990101010000 -0500')

    def test_cgtn_converts_offset_without_shifting_instant(self):
        root = schedules.xmltv(b'<tv><programme start="2099-01-01T00:00:00.000+0800" stop="2099-01-01T01:00:00.000+0800"/></tv>', 'cgtn')
        self.assertEqual(root[0].get('start'), '20981231160000 +0000')

    def test_cgtn_rejects_a_time_without_offset(self):
        with self.assertRaises(ValueError):
            schedules.xmltv(b'<tv><programme start="2099-01-01T00:00:00" stop="2099-01-01T01:00:00"/></tv>', 'cgtn')

    def test_wurl_rejects_a_start_without_timezone(self):
        source = {
            'channelData': {'id': 'channel', 'name': 'Example'},
            'movies': [{'id': 'm', 'title': {'value': 'Movie'}}],
            'schedules': [{'id': 'm', 'startDateTime': '2099-01-01T00:00:00', 'durSecs': 3600}],
        }
        with self.assertRaises(ValueError):
            schedules.wurl(json.dumps(source))

    def test_failed_or_empty_feed_preserves_last_good(self):
        config = [{'id': 'example', 'kind': 'frequency', 'url': 'https://example.test/guide'}]
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            target = output / 'example.xml'
            target.write_text('last good')
            for answer in (b'<tv/>', b'bad xml', b'<tv><programme start="20990101000000" stop="20990101010000"><title>X</title></programme></tv>'):
                with patch.object(schedules, 'fetch', return_value=answer):
                    self.assertEqual(schedules.refresh(config, output), 1)
                self.assertEqual(target.read_text(), 'last good')
            self.assertEqual(list(output.iterdir()), [target])

    def test_successful_feed_publishes_and_date_is_expanded(self):
        config = [{'id': 'example', 'kind': 'frequency', 'url': 'https://example.test/{date}'}]
        data = b'<tv><channel id="1"><display-name>Example</display-name></channel><programme channel="1" start="20990101000000 0000" stop="20990101010000 0000"><title>Program</title></programme></tv>'
        with tempfile.TemporaryDirectory() as directory, patch.object(schedules, 'fetch', return_value=data) as fetch:
            self.assertEqual(schedules.refresh(config, Path(directory)), 0)
            self.assertNotIn('{date}', fetch.call_args.args[0])
            self.assertEqual(ET.parse(Path(directory) / 'example.xml').find('programme').get('stop'), '20990101010000 +0000')

    def test_unsafe_id_is_rejected_before_fetch(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(schedules, 'fetch') as fetch:
            self.assertEqual(schedules.refresh([{'id': '../escape', 'kind': 'frequency'}], Path(directory)), 1)
            fetch.assert_not_called()


if __name__ == '__main__':
    unittest.main()
