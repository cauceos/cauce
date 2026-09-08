import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './styles/tokens.css'
import './styles/primitives.css'
import './styles/shell.css'
import './styles/session.css'
import './styles/conversation.css'
import './styles/workspace.css'
import './styles/invocations.css'
import './styles/audit.css'
import { App } from './App'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
