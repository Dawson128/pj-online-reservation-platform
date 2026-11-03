import { shallowRef } from 'vue'

// G5 owns Principal. Do not invent or cache authorization facts here.
const token = shallowRef<string | null>(null)
export const csrfToken = () => token.value
export function setCsrfToken(value: string | null): void { token.value = value }
export function clearSession(): void { token.value = null }
// Memory only: cookies remain HttpOnly and are managed by the server.
