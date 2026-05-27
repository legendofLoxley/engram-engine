import { useState, useCallback } from 'react'
import { supabase } from '../lib/supabase'

export type Message = {
  id: string
  role: 'user' | 'assistant'
  content: string
  timestamp: number
}

const API_URL = (import.meta.env.VITE_API_URL as string) || ''

export function useChat() {
  const [messages, setMessages] = useState<Message[]>([])
  const [loading, setLoading] = useState(false)
  const [sessionId] = useState(() => crypto.randomUUID())

  const sendMessage = useCallback(
    async (content: string) => {
      const userMsg: Message = {
        id: crypto.randomUUID(),
        role: 'user',
        content,
        timestamp: Date.now(),
      }
      setMessages(prev => [...prev, userMsg])
      setLoading(true)

      try {
        const {
          data: { session },
        } = await supabase.auth.getSession()
        const token = session?.access_token
        if (!token) throw new Error('Not authenticated')

        const userId = session.user.email ?? session.user.id

        const response = await fetch(`${API_URL}/cognitive/chat`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            Authorization: `Bearer ${token}`,
          },
          body: JSON.stringify({
            utterance: content,
            sessionId,
            userId,
          }),
        })

        if (!response.ok) throw new Error(`API error: ${response.status}`)

        const data = (await response.json()) as { response: string }
        const assistantMsg: Message = {
          id: crypto.randomUUID(),
          role: 'assistant',
          content: data.response,
          timestamp: Date.now(),
        }
        setMessages(prev => [...prev, assistantMsg])
      } catch {
        const errorMsg: Message = {
          id: crypto.randomUUID(),
          role: 'assistant',
          content: 'Something went wrong. Please try again.',
          timestamp: Date.now(),
        }
        setMessages(prev => [...prev, errorMsg])
      } finally {
        setLoading(false)
      }
    },
    [sessionId],
  )

  return { messages, loading, sendMessage }
}
