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

export function App() {
  const { user, loading, signInWithGoogle, signOut } = useAuth()
  const { messages, loading: chatLoading, sendMessage } = useChat()

  if (loading) return <LoadingScreen />
  if (!user) return <AuthGate onSignIn={signInWithGoogle} />

  return (
    <div className="app-layout">
      <ChatPanel
        messages={messages}
        loading={chatLoading}
        onSend={sendMessage}
        userName={user.user_metadata?.full_name as string | undefined ?? user.email}
        onSignOut={signOut}
      />
      <ArtifactPanel />
    </div>
  )
}
