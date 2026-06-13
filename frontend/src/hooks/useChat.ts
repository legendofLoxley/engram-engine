import { useState, useCallback, useEffect, useRef } from 'react'
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
  const [initializing, setInitializing] = useState(true)
  const [sessionId] = useState(() => crypto.randomUUID())
  const initCalledRef = useRef(false)

  useEffect(() => {
    if (initCalledRef.current) return
    initCalledRef.current = true

    async function initSession() {
      try {
        const {
          data: { session },
        } = await supabase.auth.getSession()
        const token = session?.access_token
        if (!token) return

        const userId = session.user.email ?? session.user.id

        const res = await fetch(`${API_URL}/cognitive/init`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            Authorization: `Bearer ${token}`,
          },
          body: JSON.stringify({
            sessionId,
            userId,
            userEmail: session.user.email ?? '',
            context: { timezone: Intl.DateTimeFormat().resolvedOptions().timeZone },
          }),
        })

        if (!res.ok) return

        const data = (await res.json()) as {
          greeting: string
          phraseId: string
          sessionId: string
          scaffoldQuestion?: string
        }

        const initMessages: Message[] = [
          {
            id: crypto.randomUUID(),
            role: 'assistant',
            content: data.greeting,
            timestamp: Date.now(),
          },
        ]
        if (data.scaffoldQuestion) {
          initMessages.push({
            id: crypto.randomUUID(),
            role: 'assistant',
            content: data.scaffoldQuestion,
            timestamp: Date.now(),
          })
        }
        setMessages(initMessages)
      } catch {
        // Non-fatal — session proceeds without greeting
      } finally {
        setInitializing(false)
      }
    }

    void initSession()
  }, [sessionId])

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

  return { messages, loading, initializing, sendMessage }
}
