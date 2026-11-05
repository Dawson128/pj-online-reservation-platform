"""Create one empty, disposable database in the isolated CI PostgreSQL service."""
import os
import re
import subprocess
from pathlib import Path

if os.environ.get('GITHUB_ACTIONS') != 'true':
    raise SystemExit('This fixture is only for the isolated CI PostgreSQL service.')
identity = os.environ.get('GITHUB_RUN_ID', '') + '_' + os.environ.get('GITHUB_RUN_ATTEMPT', '')
if not re.fullmatch(r'[0-9]+_[0-9]+', identity):
    raise SystemExit('Missing CI run identity.')
name = 'pj_g6_auth_it_' + identity
subprocess.run(['psql', '-X', '--no-password', '-h', '127.0.0.1', '-U', 'pj_dev', '-d', 'postgres',
                '-v', 'ON_ERROR_STOP=1', '-c', f'CREATE DATABASE {name} OWNER pj_dev'], check=True)
with Path(os.environ['GITHUB_ENV']).open('a', encoding='utf-8') as output:
    output.write(f'G6_AUTH_TEST_DB_URL=jdbc:postgresql://127.0.0.1:5432/{name}\n')
