"""Create disposable CI-only databases; never accepts a normal business database."""
import os
import re
import subprocess
from pathlib import Path

if os.environ.get('GITHUB_ACTIONS') != 'true':
    raise SystemExit('This fixture is only for an isolated GitHub Actions PostgreSQL service.')
run = os.environ.get('GITHUB_RUN_ID', '') + '_' + os.environ.get('GITHUB_RUN_ATTEMPT', '')
if not re.fullmatch(r'[0-9]+_[0-9]+', run):
    raise SystemExit('Missing CI run identity.')
variables = {}
for suffix, variable in [
    ('auth', 'DB_URL'), ('directory', 'G5_MANAGEMENT_TEST_DB_URL'),
    ('config', 'G5_CONFIG_TEST_DB_URL'), ('resource_facts', 'G5_RESOURCE_FACTS_TEST_DB_URL'),
    ('business', 'G5_BUSINESS_TEST_DB_URL'), ('outbox', 'G5_OUTBOX_TEST_DB_URL'),
    ('web', None), ('web_management', 'WEB_MANAGEMENT_DB_URL'),
]:
    name = f'pj_g5_auth_it_{suffix}_{run}'
    subprocess.run(['psql', '-X', '--no-password', '-h', '127.0.0.1', '-U', 'pj_dev',
                    '-d', 'postgres', '-v', 'ON_ERROR_STOP=1', '-c', f'CREATE DATABASE {name} OWNER pj_dev'], check=True)
    if variable:
        variables[variable] = f'jdbc:postgresql://127.0.0.1:5432/{name}'
    if suffix == 'web':
        variables['WEB_AUTH_DB_URL'] = f'jdbc:postgresql://127.0.0.1:5432/{name}'
variables['DB_MIGRATION_URL'] = variables['DB_URL']
with Path(os.environ['GITHUB_ENV']).open('a', encoding='utf-8') as output:
    for key, value in variables.items():
        output.write(f'{key}={value}\n')
