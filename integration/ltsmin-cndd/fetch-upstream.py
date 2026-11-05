#!/usr/bin/env python3
"""Download the exact upstream master snapshot selected in upstream.json."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import tarfile
import urllib.request

p = argparse.ArgumentParser()
p.add_argument('--out', type=Path, required=True)
a = p.parse_args()
lock = json.loads(Path(__file__).with_name('upstream.json').read_text())
a.out.mkdir(parents=True, exist_ok=False)
for archive in lock['archives']:
    url = f"https://codeload.github.com/{archive['repository']}/tar.gz/{archive['commit']}"
    data = urllib.request.urlopen(url, timeout=60).read()
    if hashlib.sha256(data).hexdigest() != archive['sha256']:
        raise SystemExit('Archive identity mismatch: ' + url)
    target = a.out / archive['directory']
    target.mkdir(parents=True, exist_ok=True)
    with tarfile.open(fileobj=io.BytesIO(data)) as tar:
        for member in tar.getmembers():
            name = Path(member.name)
            if name.is_absolute() or '..' in name.parts or not (member.isfile() or member.isdir() or member.issym()):
                raise SystemExit('Unexpected archive entry: ' + member.name)
            if member.issym():
                link = Path(member.linkname)
                if link.is_absolute() or '..' in link.parts:
                    raise SystemExit('Unexpected archive link: ' + member.name)
            if len(name.parts) == 1:
                continue
            member.name = str(Path(*name.parts[1:]))
            tar.extract(member, target)
print(lock['commit'], a.out)
