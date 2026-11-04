"""Validate G5 operation port DTOs/examples and optionally real provider results."""
import argparse,json,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'.local/contract-tools'))
from jsonschema import Draft202012Validator,FormatChecker
parser=argparse.ArgumentParser();parser.add_argument('--responses',type=Path);args=parser.parse_args()
schema=json.loads((ROOT/'contracts/local/g5-operations.schema.json').read_text(encoding='utf-8'))
Draft202012Validator.check_schema(schema)
examples=json.loads((ROOT/'contracts/local/g5-operations.examples.json').read_text(encoding='utf-8'))
assert {e['port'] for e in examples}=={'G5-L04.get','G5-L04.list','G5-L04.metrics','G5-L05.retry','G5-L07.get'}
actual=[] if not args.responses else json.loads(args.responses.read_text(encoding='utf-8-sig'))
for row in examples+actual:
 validator=Draft202012Validator({'$defs':schema['$defs'],'$ref':'#/$defs/'+row['schema']},format_checker=FormatChecker())
 validator.validate(row['data']);bad={**row['data'],'rawEvent':{}}
 assert not validator.is_valid(bad),'private/unregistered fields must be rejected'
receipt=json.loads((ROOT/'contracts/events/g5/delivery-receipt.schema.json').read_text(encoding='utf-8'))
v=Draft202012Validator(receipt,format_checker=FormatChecker());u='00000000-0000-4000-8000-000000000001'
valid={'traceId':u,'data':{'eventId':u,'duplicate':False,'acceptedAt':'2026-11-03T15:00:00.000+08:00'}};v.validate(valid)
for key in valid['data']:
 bad=json.loads(json.dumps(valid));del bad['data'][key];assert not v.is_valid(bad)
print(f'PASS: five local port examples, closed DTO/private-field negatives, {len(actual)} actual port results, minimum delivery receipts')
print('G6 receipt provider review and real consumer integration remain pending.')
