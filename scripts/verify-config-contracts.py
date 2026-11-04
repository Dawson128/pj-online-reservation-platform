"""Validate the document-derived G5 configuration contract and composed build resource."""
from pathlib import Path
import json
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / '.local/contract-tools'))
from jsonschema import Draft202012Validator
import yaml

folder = ROOT / 'contracts/config/g5'
schema = json.loads((folder / 'config-values.schema.json').read_text(encoding='utf-8'))
Draft202012Validator.check_schema(schema)
validator = Draft202012Validator(schema)
properties = {}
for owner in ('G1', 'G2', 'G6'):
    fragment = json.loads((folder / f'baseline/{owner.lower()}.schema.json').read_text(encoding='utf-8'))
    Draft202012Validator.check_schema(fragment)
    assert fragment['additionalProperties'] is False
    assert fragment['x-owner-group'] == owner
    assert fragment['x-review-status'] == 'BASELINE_PENDING_OWNER_REVIEW'
    assert set(fragment['required']) == set(fragment['properties'])
    assert not set(properties) & set(fragment['properties'])
    properties.update(fragment['properties'])
assert schema['properties'] == properties and len(properties) == 5
assert set(schema['required']) == set(properties) and schema['additionalProperties'] is False
technical = (ROOT / '需求与技术文档/技术文档v2.md').read_text(encoding='utf-8')
defaults = {key: rule['default'] for key, rule in properties.items()}
validator.validate(defaults)
checks = 1
for key, rule in properties.items():
    bounds = re.search(re.escape(key) + r':Int\[([0-9]+),([0-9]+)\]', technical)
    assert bounds and tuple(map(int, bounds.groups())) == (rule['minimum'], rule['maximum'])
    for allowed in (rule['minimum'], rule['maximum']):
        validator.validate({**defaults, key: allowed})
        checks += 1
    for invalid in (rule['minimum'] - 1, rule['maximum'] + 1, 1.5, True, str(rule['default']), None):
        assert list(validator.iter_errors({**defaults, key: invalid})), (key, invalid)
        checks += 1
    assert list(validator.iter_errors({k: v for k, v in defaults.items() if k != key}))
    checks += 1
assert list(validator.iter_errors({**defaults, 'earlyMinutes': 99}))
openapi = yaml.safe_load((ROOT / 'contracts/openapi/g5.yaml').read_text(encoding='utf-8'))
assert openapi['components']['schemas']['ConfigValues']['properties'] == properties
resource = ROOT / 'services/approval/target/classes/g5/config-values.schema.json'
assert json.loads(resource.read_text(encoding='utf-8')) == schema, 'Run the Maven build to compose the runtime validator schema'
print(f'PASS: five configuration keys, {checks + 1} default/boundary/type/closed-object checks; document, fragments, OpenAPI and build resource agree')
print('Owner review is pending; this local check is not proof of remote CI review gates.')
