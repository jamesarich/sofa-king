import unittest
from datetime import datetime

import fixture_server as fs

CLIP = [(f'live{i}.ts', 2.0) for i in range(5)]


def epoch(text):
    return datetime.fromisoformat(text.replace('Z', '+00:00')).timestamp()


class MarkedPlaylist(unittest.TestCase):
    def test_window_slides_and_never_ends(self):
        a = fs.marked_playlist(CLIP, 1000.5).splitlines()
        b = fs.marked_playlist(CLIP, 1004.5).splitlines()
        self.assertEqual(a.count('#EXTINF:2.000,'), fs.MARKED_WINDOW)
        self.assertNotIn('#EXT-X-ENDLIST', a)
        self.assertEqual(int(b[3].split(':')[1]) - int(a[3].split(':')[1]), 2)

    def test_program_date_time_is_the_first_segment(self):
        lines = fs.marked_playlist(CLIP, 1000.5).splitlines()
        stamp = next(line for line in lines if line.startswith('#EXT-X-PROGRAM-DATE-TIME'))
        first = int(lines[3].split(':')[1])
        self.assertEqual(epoch(stamp.split(':', 1)[1]), first * 2)

    def test_mark_is_in_every_overlapping_window(self):
        begin = 240 * 100
        for now in range(begin, begin + 30 + 18):
            text = fs.marked_playlist(CLIP, now + 0.5)
            self.assertIn(f'fixture-ad-{begin}', text, now)
            self.assertIn(f'DURATION={fs.BREAK_SECONDS}', text)
        self.assertNotIn('fixture-ad', fs.marked_playlist(CLIP, begin + 30 + 20.5))
        self.assertNotIn('fixture-ad', fs.marked_playlist(CLIP, begin - 1.5))

    def test_cues_bracket_the_break(self):
        begin = 240 * 100
        out = fs.marked_playlist(CLIP, begin + 4.5)
        self.assertIn(f'#EXT-X-CUE-OUT:{fs.BREAK_SECONDS}', out)
        self.assertNotIn('CUE-IN', out)
        cont = fs.marked_playlist(CLIP, begin + 28 + 0.5)
        self.assertIn('#EXT-X-CUE-OUT-CONT:', cont)
        self.assertIn('#EXT-X-CUE-IN', fs.marked_playlist(CLIP, begin + 30.5))

    def test_breaks_come_every_given_seconds(self):
        for begin in (60 * 7, 60 * 8):
            text = fs.marked_playlist(CLIP, begin + 4.5, every=60)
            self.assertIn(f'fixture-ad-{begin}', text)
        self.assertNotIn('fixture-ad', fs.marked_playlist(CLIP, 60 * 7 + 30 + 20.5, every=60))


if __name__ == '__main__':
    unittest.main()
