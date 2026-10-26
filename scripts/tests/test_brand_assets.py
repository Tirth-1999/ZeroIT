"""Asset regression checks; run with Python providing Pillow and pypdfium2."""
import json
from pathlib import Path
import plistlib
import unittest
import xml.etree.ElementTree as ET
from PIL import Image
import pypdfium2 as pdfium
ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / 'iosApp/iosApp/Assets.xcassets'

class BrandAssetsTest(unittest.TestCase):
    def test_every_ios_icon_slot_has_correct_size_opaque_pixels_and_new_mark(self):
        catalog = json.loads((ASSETS/'AppIcon.appiconset/Contents.json').read_text())
        self.assertEqual(18, len(catalog['images']))
        for slot in catalog['images']:
            with self.subTest(slot=slot):
                im = Image.open(ASSETS/'AppIcon.appiconset'/slot['filename']).convert('RGBA')
                size = int(float(slot['size'].split('x')[0]) * int(slot['scale'][0]))
                self.assertEqual((size,size), im.size)
                self.assertEqual((255,255), im.getchannel('A').getextrema())
                for position, expected in [((.1,.1),(32,32,32)),((.3,.4),(23,103,90)),((.7,.4),(146,214,189)),((.5,.8),(255,255,255)),((.5,.5),(32,32,32))]:
                    if size < 40:
                        # Small icon antialiasing shifts exact sample boundaries;
                        # verify all brand colors remain represented.
                        pixels = im.convert('RGB').get_flattened_data()
                        self.assertTrue(any(all(abs(a-b)<18 for a,b in zip(pixel,expected)) for pixel in pixels), slot)
                    else:
                        actual = im.getpixel((int(size*position[0]), int(size*position[1])))[:3]
                        self.assertTrue(all(abs(a-b)<18 for a,b in zip(actual,expected)), (slot, actual, expected))

    def test_vector_native_launch_matches_launcher_mark(self):
        pdf = pdfium.PdfDocument(ASSETS/'LaunchLogo.imageset/launch-logo.pdf')
        self.assertEqual(1, len(pdf))
        self.assertEqual((104,104), pdf[0].get_size())
        launch = pdf[0].render(scale=1024/104, fill_color=(0,0,0,0)).to_pil().convert('RGBA')
        icon = Image.open(ASSETS/'AppIcon.appiconset/icon-1024.png').convert('RGB')
        # Ignore only background corners: launch art is rounded, iOS masks its icon.
        for x,y in [(102,102),(512,256),(522,563),(512,744),(280,510),(750,329)]:
            self.assertTrue(all(abs(a-b)<5 for a,b in zip(launch.getpixel((x,y))[:3],icon.getpixel((x,y)))))
        self.assertEqual(0,launch.getpixel((0,0))[3])

    def test_android_and_svg_geometry_match_shared_source(self):
        brand=json.loads((ROOT/'branding/zeroit-mark.json').read_text())
        svg=ET.parse(ROOT/'branding/zeroit-mark.svg').getroot()
        vector=ET.parse(ROOT/'app/src/main/res/drawable/ic_zeroit.xml').getroot()
        paths=svg.findall('{http://www.w3.org/2000/svg}path')
        android=vector.findall('path')[1:]
        ns='{http://schemas.android.com/apk/res/android}'
        self.assertEqual(len(brand['shapes']),len(paths))
        for source,s,a in zip(brand['shapes'],paths,android):
            expected=' '.join(p[0]+','.join(str(n) for n in p[1:]) for p in source['segments'])
            self.assertEqual(expected,s.get('d'))
            self.assertEqual(expected,a.get(ns+'pathData'))
            self.assertEqual(source['color'],a.get(ns+'fillColor'))

    def test_zero_has_hollow_center_and_three_separate_contributions(self):
        im = Image.open(ASSETS/'AppIcon.appiconset/icon-1024.png').convert('RGB')
        for x,y in [(512,480),(512,610),(512,220),(300,633),(724,633)]:
            self.assertEqual((32,32,32),im.getpixel((x,y)))
        self.assertEqual(3,len(json.loads((ROOT/'branding/zeroit-mark.json').read_text())['shapes']))

    def test_both_launcher_names_are_zeroit_and_upgrade_identity_is_preserved(self):
        ns='{http://schemas.android.com/apk/res/android}'
        app=ET.parse(ROOT/'app/src/main/AndroidManifest.xml').getroot().find('application')
        self.assertEqual('ZeroIT',app.get(ns+'label'))
        self.assertEqual('@mipmap/ic_zeroit',app.get(ns+'icon'))
        adaptive=ET.parse(ROOT/'app/src/main/res/mipmap-anydpi-v26/ic_zeroit.xml').getroot()
        self.assertEqual('adaptive-icon',adaptive.tag)
        self.assertEqual('@drawable/ic_zeroit_foreground',adaptive.find('foreground').get(ns+'drawable'))
        with (ROOT/'iosApp/iosApp/Info.plist').open('rb') as f:info=plistlib.load(f)
        self.assertEqual('ZeroIT',info['CFBundleDisplayName'])
        self.assertIn('applicationId = "org.fairshare.app"',(ROOT/'app/build.gradle.kts').read_text())
        self.assertIn('let databaseName = "fairshare.db"',(ROOT/'iosApp/iosApp/FairshareApp.swift').read_text())

if __name__=='__main__':unittest.main(verbosity=2)
