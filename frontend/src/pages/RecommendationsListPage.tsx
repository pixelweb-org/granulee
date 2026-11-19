import { api } from '../api/client';
import { useApi } from '../hooks/useApi';
import { useSources } from '../state/sources';
import { Button } from '../components/Button';
import { OpportunitiesTable } from '../components/OpportunitiesTable';
import { PageHeader } from '../components/PageHeader';
import { EmptyState, ErrorState, Loading } from '../components/States';
import { fmtBytes, fmtInt } from '../lib/format';

export function RecommendationsListPage() {
  const { sources, loading, selectedId, selected } = useSources();
  const { data, error, reload } = useApi(() => api.opportunities(selectedId), [selectedId]);
  const total = data?.reduce((a, o) => a + o.findings, 0) ?? 0;
  const savings = data?.reduce((a, o) => a + o.savingsBytes, 0) ?? 0;

  return (
    <>
      <PageHeader
        eyebrow={`Recommendations · ${selected ? selected.name : 'All sources'}`}
        title="Recommendations"
        description={
          data?.length
            ? `${fmtInt(total)} findings in ${fmtInt(data.length)} tables, estimated savings ≈ ${fmtBytes(savings)}. Pick a table to see its recommendations or to set up a trial.`
            : 'Tables with open findings, largest estimated savings first.'
        }
      />
      {!loading && !sources.length ? (
        <EmptyState title="No data sources yet" action={<Button to="/sources">Add a data source</Button>}>
          Recommendations come from scanning a ClickHouse server.
        </EmptyState>
      ) : error && !data ? (
        <ErrorState message={error} onRetry={() => reload()} />
      ) : !data ? (
        <Loading />
      ) : data.length ? (
        <OpportunitiesTable items={data} linkTo="recommendations" />
      ) : (
        <EmptyState title="No open findings">No table has open findings. If you have not scanned yet, run a scan from the overview.</EmptyState>
      )}
    </>
  );
}
