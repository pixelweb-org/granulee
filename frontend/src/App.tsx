import { Route, Routes } from 'react-router-dom';
import { Layout } from './components/Layout';
import { EmptyState } from './components/States';
import { Button } from './components/Button';
import { OverviewPage } from './pages/OverviewPage';
import { ExplorerPage } from './pages/ExplorerPage';
import { UsagePage } from './pages/UsagePage';
import { TablePage } from './pages/TablePage';
import { RecommendationsListPage } from './pages/RecommendationsListPage';
import { RecommendationsPage } from './pages/RecommendationsPage';
import { TrialsPage } from './pages/TrialsPage';
import { TrialSetupPage } from './pages/TrialSetupPage';
import { TrialPage } from './pages/TrialPage';
import { SourcesPage } from './pages/SourcesPage';

export function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<OverviewPage />} />
        <Route path="explorer" element={<ExplorerPage />} />
        <Route path="explorer/:sourceId" element={<ExplorerPage />} />
        <Route path="explorer/:sourceId/:database" element={<ExplorerPage />} />
        <Route path="tables/:sourceId/:database/:table" element={<TablePage />} />
        <Route path="tables/:sourceId/:database/:table/usage" element={<UsagePage />} />
        <Route path="recommendations" element={<RecommendationsListPage />} />
        <Route path="recommendations/:sourceId/:database/:table" element={<RecommendationsPage />} />
        <Route path="trials" element={<TrialsPage />} />
        <Route path="trials/new" element={<TrialSetupPage />} />
        <Route path="trials/:id" element={<TrialPage />} />
        <Route path="sources" element={<SourcesPage />} />
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
