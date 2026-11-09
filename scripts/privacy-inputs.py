"""Preserve built privacy manifests for review; not Xcode's aggregate report."""
import json
import plistlib
import shutil
import sys
from pathlib import Path

app, output = map(Path, sys.argv[1:3])
sdk_artifacts = Path(sys.argv[3]) if len(sys.argv) > 3 else app
output.mkdir(parents=True, exist_ok=True)
manifests = sorted(app.rglob('PrivacyInfo.xcprivacy'))
if not (app / 'PrivacyInfo.xcprivacy').is_file():
    raise SystemExit('Relaycast privacy manifest was not bundled')
if not any('GoogleCast' in str(path.relative_to(app)) for path in manifests):
    raise SystemExit('Google Cast privacy manifest was not bundled; review SDK packaging')
inventory = []
for path in manifests:
    relative = path.relative_to(app)
    with path.open('rb') as stream:
        manifest = plistlib.load(stream)
    inventory.append({'path': str(relative), 'manifest': manifest})
    target = output / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(path, target)
for path in sdk_artifacts.rglob('NutritionLabel.txt'):
    target = output / 'sdk' / path.relative_to(sdk_artifacts)
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(path, target)
(output / 'inventory.json').write_text(json.dumps(inventory, indent=2), encoding='utf-8')
print(f'Preserved {len(manifests)} manifests. Generate and review the aggregate report in Xcode Organizer.')
