import { BrowserRouter, Route, Routes } from 'react-router'
import { AppShell } from './components/shell/AppShell'
import { RequireSession } from './session/RequireSession'
import { LedgerProvider } from './session/LedgerContext'
import { SessionProvider } from './session/SessionContext'
import { AgentsView } from './views/agents/AgentsView'
import { ApiKeysView } from './views/api-keys/ApiKeysView'
import { AuditChainView } from './views/audit/AuditChainView'
import { ConversationView } from './views/conversation/ConversationView'
import { InvocationsView } from './views/invocations/InvocationsView'
import { SessionView } from './views/session/SessionView'
import { TenantsView } from './views/tenants/TenantsView'

export function App() {
  return (
    <BrowserRouter>
      <SessionProvider>
        <LedgerProvider>
        <Routes>
          <Route element={<AppShell />}>
            <Route index element={<SessionView />} />
            <Route
              path="/tenants"
              element={
                <RequireSession>
                  <TenantsView />
                </RequireSession>
              }
            />
            <Route
              path="/agents"
              element={
                <RequireSession>
                  <AgentsView />
                </RequireSession>
              }
            />
            <Route
              path="/api-keys"
              element={
                <RequireSession>
                  <ApiKeysView />
                </RequireSession>
              }
            />
            <Route
              path="/conversation"
              element={
                <RequireSession>
                  <ConversationView />
                </RequireSession>
              }
            />
            <Route
              path="/invocations"
              element={
                <RequireSession>
                  <InvocationsView />
                </RequireSession>
              }
            />
            <Route
              path="/audit"
              element={
                <RequireSession>
                  <AuditChainView />
                </RequireSession>
              }
            />
          </Route>
        </Routes>
        </LedgerProvider>
      </SessionProvider>
    </BrowserRouter>
  )
}
