/// <reference types="vitest/globals" />
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { vi, describe, it, expect, beforeEach, afterEach } from 'vitest'
import App from '../App'

// ---------------------------------------------------------------------------
// fetch mock helpers
// ---------------------------------------------------------------------------

function mockFetchSuccess(body: object) {
  return vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce({
    ok: true,
    json: async () => body,
  } as Response)
}

function mockFetchFailure() {
  return vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce({
    ok: false,
    json: async () => ({}),
  } as Response)
}

// ---------------------------------------------------------------------------
// Suppress window.alert — jsdom throws otherwise
// ---------------------------------------------------------------------------
beforeEach(() => {
  vi.spyOn(window, 'alert').mockImplementation(() => {})
})

afterEach(() => {
  vi.restoreAllMocks()
})

// ---------------------------------------------------------------------------
// Initial render
// ---------------------------------------------------------------------------

describe('App – initial render', () => {
  it('shows welcome message', () => {
    render(<App />)
    expect(screen.getByText(/Hello! I am your Senior Agentic RAG assistant/i)).toBeInTheDocument()
  })

  it('send button is disabled when input is empty', () => {
    render(<App />)
    const btn = screen.getByRole('button', { name: '' }) // Send icon button
    // The button has disabled attribute when input is empty
    const input = screen.getByPlaceholderText(/Ask a question/i)
    expect(input).toHaveValue('')
    // button should be disabled
    expect(btn).toBeDisabled()
  })
})

// ---------------------------------------------------------------------------
// Sending a message
// ---------------------------------------------------------------------------

describe('App – sending a message', () => {
  it('clears input, appends user message, then appends assistant response', async () => {
    const fetchSpy = mockFetchSuccess({
      answer: 'The answer is 42',
      sourceDocuments: [],
    })

    render(<App />)
    const input = screen.getByPlaceholderText(/Ask a question/i)
    await userEvent.type(input, 'What is life?')
    await userEvent.click(screen.getByRole('button', { name: '' }))

    expect(input).toHaveValue('')
    expect(screen.getByText('What is life?')).toBeInTheDocument()

    await waitFor(() =>
      expect(screen.getByText('The answer is 42')).toBeInTheDocument()
    )

    expect(fetchSpy).toHaveBeenCalledWith(
      '/api/chat',
      expect.objectContaining({ method: 'POST' })
    )
  })

  it('pressing Enter submits the message', async () => {
    mockFetchSuccess({ answer: 'Enter works', sourceDocuments: [] })

    render(<App />)
    const input = screen.getByPlaceholderText(/Ask a question/i)
    await userEvent.type(input, 'press enter test{Enter}')

    await waitFor(() =>
      expect(screen.getByText('Enter works')).toBeInTheDocument()
    )
  })

  it('shows error message when fetch fails', async () => {
    mockFetchFailure()

    render(<App />)
    const input = screen.getByPlaceholderText(/Ask a question/i)
    await userEvent.type(input, 'will fail{Enter}')

    await waitFor(() =>
      expect(
        screen.getByText(/I'm sorry, I encountered an error/i)
      ).toBeInTheDocument()
    )
  })
})

// ---------------------------------------------------------------------------
// File upload
// ---------------------------------------------------------------------------

describe('App – file upload', () => {
  it('calls POST /api/documents with FormData and shows success alert', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce({
      ok: true,
    } as Response)

    render(<App />)
    const fileInput = document.querySelector('input[type="file"]') as HTMLInputElement
    const file = new File(['content'], 'test.pdf', { type: 'application/pdf' })

    await act(async () => {
      fireEvent.change(fileInput, { target: { files: [file] } })
    })

    await waitFor(() => expect(fetchSpy).toHaveBeenCalled())

    const [url, opts] = fetchSpy.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/documents')
    expect((opts as RequestInit).method).toBe('POST')
    expect((opts as RequestInit).body).toBeInstanceOf(FormData)
    expect(window.alert).toHaveBeenCalledWith(
      expect.stringContaining('uploaded successfully')
    )
  })

  it('does not call fetch when no file is selected', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch')

    render(<App />)
    const fileInput = document.querySelector('input[type="file"]') as HTMLInputElement

    await act(async () => {
      fireEvent.change(fileInput, { target: { files: [] } })
    })

    expect(fetchSpy).not.toHaveBeenCalled()
  })
})
