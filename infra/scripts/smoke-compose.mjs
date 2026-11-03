const base = process.env.SMOKE_BASE_URL ?? 'http://127.0.0.1:8080'
const page = await fetch(`${base}/`, { signal: AbortSignal.timeout(10000) })
if (!page.ok || !(await page.text()).includes('校园资源服务平台')) throw new Error('Web shell unavailable')
for (const path of ['/internal/events', '/actuator/health', '/api/not-a-service']) {
  const response = await fetch(`${base}${path}`)
  if (response.status !== 404) throw new Error(`Gateway exposed an unexpected path: ${path} => ${response.status}`)
}
for (const module of ['m1','m2','m3']) {
  const response = await fetch(`${base}/api/${module}/not-yet-implemented`)
  const body = await response.json()
  if (response.status !== 401 || body.code !== 'UNAUTHENTICATED') throw new Error(`Authentication boundary failed for ${module}`)
}
console.log('Compose web, three service proxies and private-route boundaries passed. No business-flow readiness claimed.')
