import { useState, useRef, useEffect } from 'react'
import { Upload, Send } from 'lucide-react'
import './index.css'

interface Message {
  id: string
  role: 'user' | 'assistant'
  content: string
  sources?: string[]
}

function App() {
  const [messages, setMessages] = useState<Message[]>([
    {
      id: 'welcome',
      role: 'assistant',
      content: 'Hello! I am your Senior Agentic RAG assistant. How can I help you today?'
    }
  ])
  const [input, setInput] = useState('')
  const [isLoading, setIsLoading] = useState(false)
  const [isUploading, setIsUploading] = useState(false)
  const chatEndRef = useRef<HTMLDivElement>(null)
  
  const scrollToBottom = () => {
    chatEndRef.current?.scrollIntoView({ behavior: "smooth" })
  }

  useEffect(() => {
    scrollToBottom()
  }, [messages, isLoading])

  const handleSend = async () => {
    if (!input.trim() || isLoading) return

    const userMessage: Message = {
      id: Date.now().toString(),
      role: 'user',
      content: input
    }
    
    setMessages(prev => [...prev, userMessage])
    setInput('')
    setIsLoading(true)

    try {
      const response = await fetch('/api/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          query: userMessage.content,
          userId: 'dev1' // Hardcoded user for local dev
        })
      })

      if (!response.ok) throw new Error('Failed to fetch response')
      
      const data = await response.json()
      
      setMessages(prev => [...prev, {
        id: (Date.now() + 1).toString(),
        role: 'assistant',
        content: data.answer,
        sources: data.sourceDocuments
      }])
    } catch (error) {
      console.error(error)
      setMessages(prev => [...prev, {
        id: (Date.now() + 1).toString(),
        role: 'assistant',
        content: "I'm sorry, I encountered an error connecting to the AI Engine."
      }])
    } finally {
      setIsLoading(false)
    }
  }

  const handleFileUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file) return

    setIsUploading(true)
    const formData = new FormData()
    formData.append('file', file)
    formData.append('userId', 'dev1')

    try {
      const resp = await fetch('/api/documents', {
        method: 'POST',
        body: formData
      })
      
      if (resp.ok) {
        alert("File uploaded successfully. Indexing in background...")
      } else {
        alert("Failed to upload document.")
      }
    } catch (err) {
      console.error(err)
      alert("Error uploading document.")
    } finally {
      setIsUploading(false)
      // reset file input
      e.target.value = ''
    }
  }

  return (
    <div className="app-container">
      <header>
        <h1>polyglot.ai</h1>
        <label className="upload-btn">
          <Upload size={16} />
          {isUploading ? 'Uploading...' : 'Upload Document'}
          <input 
            type="file" 
            accept=".pdf,.md,.txt" 
            onChange={handleFileUpload} 
            style={{ display: 'none' }} 
            disabled={isUploading}
          />
        </label>
      </header>

      <div className="chat-container">
        {messages.map((msg) => (
          <div key={msg.id} className={`message ${msg.role}`}>
            <div className="content">
              {msg.content}
            </div>
            {msg.sources && msg.sources.length > 0 && (
              <div className="sources">
                Sources: {msg.sources.join(', ')}
              </div>
            )}
          </div>
        ))}
        {isLoading && (
          <div className="message assistant">
            <div className="typing-indicator">
              <span></span><span></span><span></span>
            </div>
          </div>
        )}
        <div ref={chatEndRef} />
      </div>

      <div className="input-area">
        <input
          type="text"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && handleSend()}
          placeholder="Ask a question..."
          disabled={isLoading}
        />
        <button 
          className="send-btn" 
          onClick={handleSend} 
          disabled={!input.trim() || isLoading}
        >
          <Send size={20} />
        </button>
      </div>
    </div>
  )
}

export default App
