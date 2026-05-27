import type { Message } from '../hooks/useChat'

interface MessageBubbleProps {
  message: Message
}

export function MessageBubble({ message }: MessageBubbleProps) {
  const isUser = message.role === 'user'
  return (
    <div className={`message-row ${isUser ? 'message-row--user' : 'message-row--assistant'}`}>
      {!isUser && <div className="message-avatar">a</div>}
      <div className={`message-bubble ${isUser ? 'bubble--user' : 'bubble--assistant'}`}>
        <p className="message-content">{message.content}</p>
      </div>
    </div>
  )
}
