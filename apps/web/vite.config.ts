import { defineConfig, type ProxyOptions } from 'vite'
import vue from '@vitejs/plugin-vue'
import basicSsl from '@vitejs/plugin-basic-ssl'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { randomUUID } from 'node:crypto'
const root = fileURLToPath(new URL('../..', import.meta.url))
const approval = process.env.APPROVAL_PROXY_TARGET ?? 'http://127.0.0.1:8082'
if (!/^http:\/\/(127\.0\.0\.1|localhost):[0-9]{1,5}$/.test(approval)) throw new Error('Local approval proxy must use loopback HTTP')
const allowed = /^\/api\/m2\/(?:auth\/(?:login|logout|me|change-password)|departments|admin\/(?:accounts(?:\/[0-9a-f-]{36}(?:\/roles)?)?|roles(?:\/[0-9a-f-]{36})?|permissions|config|departments(?:\/[0-9a-f-]{36})?))$/
const proxy: Record<string, string | ProxyOptions> = {
  '/api/m1': 'http://127.0.0.1:8081',
  '/api/m2': { target: approval, changeOrigin: false, timeout: 8000, proxyTimeout: 8000,
    configure(instance) {
      instance.prependListener('error', (_error: unknown, _request: unknown, response: any) => {
        if (response && 'writeHead' in response && !response.headersSent) {
          response.writeHead(503, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' })
          response.end(JSON.stringify({ code: 'AUTH_UNAVAILABLE', message: '身份服务暂不可用，请稍后重试', traceId: randomUUID(), details: { fields: [], conflicts: [], currentVersion: null, operationId: null, retryAfterSeconds: null } }))
        }
      })
    },
  },
  '/api/m3': 'http://127.0.0.1:8083',
}
function boundary(request: { url?: string }, response: { statusCode: number; end: (body: string) => void }, next: () => void) {
  const path = new URL(request.url ?? '/', 'http://localhost').pathname
  if (/^\/(internal|actuator)(\/|$)/.test(path) || path.startsWith('/api/m2/') && !allowed.test(path)) { response.statusCode = 404; response.end('Not found') } else next()
}
export default defineConfig({
  plugins: [vue(), basicSsl({ name: 'pj-campus-local', domains: ['localhost', '127.0.0.1'], certDir: resolve(root, '.local/https') }), {
    name: 'private-path-boundary', configureServer(server) { server.middlewares.use(boundary) }, configurePreviewServer(server) { server.middlewares.use(boundary) },
  }],
  server: { host: '127.0.0.1', port: 5173, strictPort: true, proxy },
  preview: { host: '127.0.0.1', port: 5173, strictPort: true, proxy },
})
