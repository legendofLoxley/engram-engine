import type { User } from '@supabase/supabase-js'
import { useAuth } from './hooks/useAuth'
import { useChat } from './hooks/useChat'
import { AuthGate } from './components/AuthGate'
import { ChatPanel } from './components/ChatPanel'
import { ArtifactPanel } from './components/ArtifactPanel'

function LoadingScreen() {
  return (
    <div className="loading-screen">
      <div className="loading-logo">alfrd</div>
    </div>
  )
}

function AuthenticatedApp({ user, onSignOut }: { user: User; onSignOut: () => void }) {
  const { messages, loading: chatLoading, initializing, sendMessage } = useChat()

  return (
    <div className="app-layout">
      <ChatPanel
        messages={messages}
        loading={chatLoading}
        initializing={initializing}
        onSend={sendMessage}
        userName={user.user_metadata?.full_name as string | undefined ?? user.email}
        onSignOut={onSignOut}
      />
      <ArtifactPanel />
    </div>
  )
}

export function App() {
  const { user, loading, signInWithGoogle, signOut } = useAuth()

  if (loading) return <LoadingScreen />
  if (!user) return <AuthGate onSignIn={signInWithGoogle} />
  return <AuthenticatedApp user={user} onSignOut={signOut} />
}
