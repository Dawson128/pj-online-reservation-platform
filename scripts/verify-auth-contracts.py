"""Validate G5 schemas/examples; optionally validate real, sanitized outbox events."""
from pathlib import Path
import argparse
import json
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / '.local' / 'contract-tools'))
try:
    import yaml
    from jsonschema import Draft202012Validator, FormatChecker
except ImportError:
    sys.exit('Install scripts/requirements-contracts.txt into .local/contract-tools first.')


def pointer(document, reference):
    assert reference.startswith('#/'), f'Only repository-local schema references are allowed: {reference}'
    value = document
    for part in reference[2:].split('/'):
        value = value[part.replace('~1', '/').replace('~0', '~')]
    return value


def resolve(document, value):
    return pointer(document, value['$ref']) if '$ref' in value else value


def check_references(document, value):
    if isinstance(value, dict):
        if '$ref' in value:
            pointer(document, value['$ref'])
        for child in value.values():
            check_references(document, child)
    elif isinstance(value, list):
        for child in value:
            check_references(document, child)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--events', type=Path, help='JSON array of actual G5 AuditRecorded events')
    args = parser.parse_args()
    document = yaml.safe_load((ROOT / 'contracts/openapi/g5.yaml').read_text(encoding='utf-8'))
    check_references(document, document)
    operations = {operation['operationId'] for path in document['paths'].values() for operation in path.values()}
    assert operations == {f'G5-P{i:02d}' for i in range(1, 20)} | {f'G5-I{i:02d}' for i in range(1, 11)}
    samples = json.loads((ROOT / 'contracts/openapi/examples/g5-auth.json').read_text(encoding='utf-8'))
    samples += json.loads((ROOT / 'contracts/openapi/examples/g5-management.json').read_text(encoding='utf-8'))
    samples += json.loads((ROOT / 'contracts/openapi/examples/g5-config.json').read_text(encoding='utf-8'))
    samples += json.loads((ROOT / 'contracts/openapi/examples/g5-resource-facts.json').read_text(encoding='utf-8'))
    samples += json.loads((ROOT / 'contracts/openapi/examples/g5-business-authorization.json').read_text(encoding='utf-8'))
    covered = set()
    for sample in samples:
        operation = document['paths'][sample['path']][sample['method']]
        response = resolve(document, operation['responses'][sample['status']])
        schema = response['content']['application/json']['schema']
        Draft202012Validator({'components': document['components'], **schema}, format_checker=FormatChecker()).validate(sample['body'])
        if 'request' in sample:
            request_schema = operation['requestBody']['content']['application/json']['schema']
            Draft202012Validator({'components': document['components'], **request_schema}, format_checker=FormatChecker()).validate(sample['request'])
        covered.add((operation['operationId'], sample['status'].startswith('2')))
    assert covered == {(operation, success) for operation in operations for success in (False, True)}
    print(f'PASS: OpenAPI references and {len(samples)} complete success/failure response examples')

    incoming = json.loads((ROOT / 'contracts/events/g5/IncomingNotificationEvent.schema.json').read_text(encoding='utf-8'))
    registry = json.loads((ROOT / 'contracts/events/g5/notification-registry.json').read_text(encoding='utf-8'))
    events = json.loads((ROOT / 'contracts/events/g5/examples/notification-events.json').read_text(encoding='utf-8'))
    Draft202012Validator.check_schema(incoming)
    validator = Draft202012Validator(incoming, format_checker=FormatChecker())
    assert set(registry) == {event['eventType'] for event in events} and len(events) == 19
    assert incoming['oneOf'] == document['components']['schemas']['IncomingNotificationEvent']['oneOf']
    for event in events:
        validator.validate(event)
        audit = event['audit']
        assert audit['id'] == event['eventId'] and audit['source'] == event['aggregate']
        for key in ('producer','roomId','actorId','actorKind','traceId','operationId'):
            assert audit[key] == event[key]
        rule = registry[event['eventType']]
        if rule['producer']: assert event['producer'] == rule['producer']
        if rule['sourceType']: assert event['aggregate']['type'] == rule['sourceType']
        if rule['sourceKey']: assert event['aggregate']['id'] == event['payload'][rule['sourceKey']]
        for section in ('envelope','payload'):
            invalid = json.loads(json.dumps(event))
            (invalid if section == 'envelope' else invalid['payload'])['unknownField'] = True
            assert not validator.is_valid(invalid)
    print('PASS: 19 notification events, closed payload/envelope negatives and provider/source/audit links')

    event_schema = json.loads((ROOT / 'contracts/events/g5/AuditRecorded.schema.json').read_text(encoding='utf-8'))
    Draft202012Validator.check_schema(event_schema)
    if args.events:
        events = json.loads(args.events.read_text(encoding='utf-8-sig'))
        assert events, 'Actual event evidence must not be empty'
        validator = Draft202012Validator(event_schema)
        config_validator = Draft202012Validator(json.loads((ROOT / 'contracts/events/g5/ConfigChanged.schema.json').read_text(encoding='utf-8')))
        for event in events:
            (config_validator if event['eventType'] == 'ConfigChanged' else validator).validate(event)
            if event['eventType'] == 'ConfigChanged':
                assert event['payload']['configVersion'] == event['aggregateVersion']
                assert event['payload']['effectiveAt'] == event['audit']['effectiveAt']
            audit = event['audit']
            assert event['eventId'] == audit['id']
            assert event['aggregate'] == audit['source']
            for name in ('producer', 'roomId', 'actorId', 'actorKind', 'traceId', 'operationId'):
                assert event[name] == audit[name], f'Envelope/audit mismatch: {name}'
        print(f'PASS: {len(events)} actual G5 events and envelope links')
    else:
        print('PASS: AuditRecorded schema; actual event validation not requested')


if __name__ == '__main__':
    main()
