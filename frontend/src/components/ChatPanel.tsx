import { useState, useRef, useEffect, type KeyboardEvent } from 'react'
import type { Message } from '../hooks/useChat'
import { MessageBubble } from './MessageBubble'

interface ChatPanelProps {
  messages: Message[]
  loading: boolean
  onSend: (content: string) => void
  userName: string | undefined
  onSignOut: () => void
}

export function ChatPanel({ messages, loading, onSend, userName, onSignOut }: ChatPanelProps) {
  const [input, setInput] = useState('')
  const bottomRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages, loading])

  const submit = () => {
    const text = input.trim()
    if (!text || loading) return
    setInput('')
    onSend(text)
  }

  const handleKey = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      submit()
    }
  }

  return (
    <div className="chat-panel">
      <div className="chat-header">
        <span className="chat-header-logo">alfrd</span>
        <div className="chat-header-right">
          {userName && <span className="chat-user">{userName}</span>}
          <button className="btn-signout" onClick={onSignOut}>
            Sign out
          </button>
        </div>
      </div>

      <div className="chat-messages">
        {messages.length === 0 && (
          <div className="chat-empty">
            <p>Start a conversation with alfrd.</p>
          </div>
        )}
        {messages.map(msg => (
          <MessageBubble key={msg.id} message={msg} />
        ))}
        {loading && (
          <div className="message-row message-row--assistant">
            <div className="message-avatar">a</div>
            <div className="message-bubble bubble--assistant bubble--typing">
              <span className="typing-dot" />
              <span className="typing-dot" />
              <span className="typing-dot" />
            </div>
          </div>
        )}
        <div ref={bottomRef} />
      </div>

      <div className="chat-input-area">
        <textarea
          className="chat-input"
          value={input}
          onChange={e => setInput(e.target.value)}
          onKeyDown={handleKey}
          placeholder="Message alfrd…"
          rows={1}
          disabled={loading}
        />
        <button className="btn-send" onClick={submit} disabled={!input.trim() || loading}>
          ↑
        </button>
      </div>
    </div>
  )
}
