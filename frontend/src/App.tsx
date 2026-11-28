import { Route, Routes } from 'react-router-dom';
import { Layout } from './components/Layout';
import { EmptyState } from './components/States';
import { Button } from './components/Button';
import { OverviewPage } from './pages/OverviewPage';
import { ExplorerPage } from './pages/ExplorerPage';
import { IndexesPage } from './pages/IndexesPage';
import { UsagePage } from './pages/UsagePage';
import { TablePage } from './pages/TablePage';
import { RecommendationsListPage } from './pages/RecommendationsListPage';
import { RecommendationsPage } from './pages/RecommendationsPage';
import { TrialsPage } from './pages/TrialsPage';
import { TrialSetupPage } from './pages/TrialSetupPage';
import { TrialPage } from './pages/TrialPage';
import { SourcesPage } from './pages/SourcesPage';
import { UsersPage } from './pages/UsersPage';
import { SignInPage, OwnPasswordPage } from './pages/SignInPage';
import { ErrorState, Loading } from './components/States';
import { SourcesProvider } from './state/sources';
import { useAuth } from './state/auth';

/** Nothing of the app is shown, and nothing of it is loaded, before somebody is signed in with a password of their own. */
export function App() {
  const { me, loading, error, retry } = useAuth();
  if (loading || error) {
    return <div className="auth">{error ? <ErrorState message={error} onRetry={retry} /> : <Loading />}</div>;
  }
  if (!me) return <SignInPage />;
  if (me.mustChangePassword) return <OwnPasswordPage />;
  return (
    // Keyed by the user: signing in as somebody else starts from nothing.
    <SourcesProvider key={me.id} userId={me.id}>
      <Pages admin={me.role === 'admin'} />
    </SourcesProvider>
  );
}

function Pages({ admin }: { admin: boolean }) {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<OverviewPage />} />
        <Route path="explorer" element={<ExplorerPage />} />
        <Route path="explorer/:sourceId" element={<ExplorerPage />} />
        <Route path="explorer/:sourceId/:database" element={<ExplorerPage />} />
        <Route path="tables/:sourceId/:database/:table" element={<TablePage />} />
        <Route path="tables/:sourceId/:database/:table/indexes" element={<IndexesPage />} />
        <Route path="tables/:sourceId/:database/:table/usage" element={<UsagePage />} />
        <Route path="recommendations" element={<RecommendationsListPage />} />
        <Route path="recommendations/:sourceId/:database/:table" element={<RecommendationsPage />} />
        <Route path="trials" element={<TrialsPage />} />
        <Route path="trials/new" element={<TrialSetupPage />} />
        <Route path="trials/:id/:tab?" element={<TrialPage />} />
        <Route path="sources" element={<SourcesPage />} />
        {admin ? <Route path="users" element={<UsersPage />} /> : null}
        <Route
          path="*"
          element={
            <EmptyState title="Page not found" action={<Button to="/">Go to the overview</Button>}>
              There is nothing at this address.
            </EmptyState>
          }
        />
      </Route>
    </Routes>
  );
}
