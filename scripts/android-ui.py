"""Print a bounded UI hierarchy without logging editable field contents."""
import os
import re
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path

adb = [os.environ["REA_ADB_PATH"], "-s", os.environ.get("ANDROID_SERIAL", "emulator-5554")]
remote = f"/sdcard/Download/rea-ui-{uuid.uuid4().hex}.xml"
for attempt in range(3):
    try:
        subprocess.run(adb + ["shell", "uiautomator", "dump", remote], check=True, capture_output=True, timeout=20)
        raw = subprocess.check_output(adb + ["exec-out", "cat", remote], timeout=10)
        root = ET.fromstring(raw)
        break
    except (subprocess.SubprocessError, ET.ParseError):
        if attempt == 2:
            raise RuntimeError("Could not capture a valid UI hierarchy after three attempts; check device connectivity and screen readiness.")
        time.sleep(0.5)
    finally:
        subprocess.run(adb + ["shell", "rm", "-f", remote], check=False, capture_output=True, timeout=10)
for node in root.iter("node"):
    editable = "EditText" in node.get("class", "") or node.get("password") == "true"
    for attr in ("text", "content-desc"):
        value = node.get(attr, "")
        if editable and value:
            node.set(attr, "<editable field>")
        else:
            node.set(attr, re.sub(r"[^\s@]+@[^\s@]+\.[^\s@]+", "<email>", value))
    if node.get("text") or node.get("content-desc") or node.get("clickable") == "true":
        print(node.get("text"), node.get("content-desc"), node.get("resource-id"), node.get("bounds"))
if len(sys.argv) > 1:
    ET.ElementTree(root).write(Path(sys.argv[1]), encoding="utf-8", xml_declaration=True)
