"""Validate shipped SVG geometry independently of the native UI screenshot tests."""
import re
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
NS = '{http://www.w3.org/2000/svg}'


def luminance(hex_color):
    values = [int(hex_color[i:i+2], 16) / 255 for i in (1, 3, 5)]
    return sum(weight * (x / 12.92 if x <= .04045 else ((x + .055) / 1.055) ** 2.4)
               for weight, x in zip((.2126, .7152, .0722), values))


class GroupPeopleSvgTest(unittest.TestCase):
    def test_every_member_count_in_both_appearances_has_complete_safe_geometry(self):
        files = sorted((ROOT / 'assets/group-people').glob('*.svg'))
        self.assertEqual(len(files), 12)
        for count in range(1, 7):
            for appearance in ('light', 'dark'):
                with self.subTest(count=count, appearance=appearance):
                    root = ET.parse(ROOT / f'assets/group-people/people-{count}-{appearance}.svg').getroot()
                    self.assertEqual(root.attrib['viewBox'], '0 0 120 76')
                    self.assertEqual(root.attrib['aria-label'], f'{count} group members')
                    heads, bodies = root.findall(NS+'circle'), root.findall(NS+'path')
                    self.assertEqual(len(heads), count)
                    self.assertEqual(len(bodies), count)
                    self.assertEqual(len(root), count * 2)
                    background = '#101010' if appearance == 'dark' else '#F7F6F0'
                    for item in list(root):
                        self.assertRegex(item.attrib['fill'], r'^#[0-9A-Fa-f]{6}$')
                        foreground, back = sorted((luminance(item.attrib['fill']), luminance(background)))
                        self.assertGreaterEqual((back+.05)/(foreground+.05), 3)
                    for head in heads:
                        x,y,r = (float(head.attrib[k]) for k in ('cx','cy','r'))
                        self.assertTrue(r > 0 and 0 <= x-r < x+r <= 120 and 0 <= y-r < y+r <= 76)
                    for body in bodies:
                        # Restrict to our local vector vocabulary: no scripts, images or links.
                        self.assertRegex(body.attrib['d'], r'^M[0-9 .]+v-5C[0-9 .]+v5Z$')
                        coords = [float(x) for x in re.findall(r'\d+(?:\.\d+)?', body.attrib['d'].replace('v-5','').replace('v5',''))]
                        self.assertTrue(all(0 <= x <= (120 if i % 2 == 0 else 76) for i,x in enumerate(coords)))

    def test_light_dark_pairs_keep_identical_geometry(self):
        for count in range(1,7):
            variants = []
            for mode in ('light','dark'):
                root=ET.parse(ROOT / f'assets/group-people/people-{count}-{mode}.svg').getroot()
                variants.append([(item.tag, {k:v for k,v in item.attrib.items() if k != 'fill'}) for item in root])
            self.assertEqual(*variants)

if __name__ == '__main__':
    unittest.main()
