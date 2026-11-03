export function startPolling(task: (signal: AbortSignal) => Promise<void>, intervalMs = 5000,
  onError: (error: unknown) => void = () => {}): () => void {
  if (intervalMs < 1000) throw new Error('轮询间隔至少 1000ms')
  let stopped = false
  let timer: ReturnType<typeof setTimeout> | undefined
  let active: AbortController | undefined
  async function tick(): Promise<void> {
    if (stopped || document.hidden) return
    active = new AbortController()
    try { await task(active.signal) }
    catch (error) { if (!active.signal.aborted) onError(error) }
    finally {
      active = undefined
      if (!stopped && !document.hidden) timer = setTimeout(() => { void tick() }, intervalMs)
    }
  }
  function visibilityChanged(): void {
    clearTimeout(timer)
    if (document.hidden) active?.abort()
    else if (!active) void tick()
  }
  document.addEventListener('visibilitychange', visibilityChanged)
  void tick()
  return () => {
    stopped = true; clearTimeout(timer); active?.abort()
    document.removeEventListener('visibilitychange', visibilityChanged)
  }
}
