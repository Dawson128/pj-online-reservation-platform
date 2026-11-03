import { readdir, readFile } from 'node:fs/promises'
import { join } from 'node:path'
const root = new URL('../../', import.meta.url)
const errors = []
const versions = new Map()
async function walk(path) {
  for (const entry of await readdir(new URL(path, root), { withFileTypes: true })) {
    const relative = `${path}${entry.name}`
    if (entry.isDirectory()) await walk(`${relative}/`)
    else if (entry.name.endsWith('.sql')) {
      const match = /^V(\d{8}_\d{6}_[1-6]_\d{3})__G([1-6])_[A-Za-z0-9_]+\.sql$/.exec(entry.name)
      const group = relative.match(/^db\/(m[123])\/g([1-6])\//)
      if (!match || !group || match[2] !== group[2] || match[1].split('_')[2] !== group[2]) {
        errors.push(`Invalid migration name/owner: ${relative}`); continue
      }
      const key = `${group[1]}:${match[1]}`
      if (versions.has(key)) errors.push(`Duplicate migration version: ${relative} and ${versions.get(key)}`)
      versions.set(key, relative)
    }
  }
}
await walk('db/')
// Complementary guard only: semantic SQL ownership remains an owner review obligation.
for (const service of ['reservation', 'approval', 'maintenance']) {
  const dir = new URL(`services/${service}/`, root)
  async function inspect(path) {
    for (const entry of await readdir(new URL(path, dir), { withFileTypes: true })) {
      if (['target', 'node_modules'].includes(entry.name)) continue
      const file = `${path}${entry.name}`
      if (entry.isDirectory()) { await inspect(`${file}/`); continue }
      if (!entry.name.endsWith('.java')) continue
      const source = await readFile(new URL(file, dir), 'utf8')
      const owner = file.match(/^modules\/g([1-6])\//)?.[1]
      for (const imp of source.matchAll(/import\s+(?:static\s+)?cn\.edu\.campus\.\w+\.g([1-6])\.([\w.]+);/g)) {
        if (owner && imp[1] !== owner && !imp[2].startsWith('api.')) errors.push(`Cross-module internal import: ${service}/${file}`)
      }
    }
  }
  await inspect('')
}
if (errors.length) { errors.forEach(error => console.error(error)); process.exit(1) }
console.log(`Repository checks passed; ${versions.size} versioned SQL migrations present. Business tests are tracked separately.`)
