import '@testing-library/jest-dom'
import { vi } from 'vitest'

// jsdom does not implement scrollIntoView — mock it globally so React components
// that call chatEndRef.current?.scrollIntoView(...) don't throw in tests.
window.HTMLElement.prototype.scrollIntoView = vi.fn()
