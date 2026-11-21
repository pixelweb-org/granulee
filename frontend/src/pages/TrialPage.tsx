import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { api } from '../api/client';
import { useApi, usePolling } from '../hooks/useApi';
import { Button, Choice } from '../components/Button';
import { Card } from '../components/Card';
import { PageHeader } from '../components/PageHeader';
import { ErrorState, Loading } from '../components/States';
import { Status } from '../components/StatusDot';
import { TrialApply } from '../components/TrialApply';
import { TrialCodecs } from '../components/TrialCodecs';
import { TrialSpeed } from '../components/TrialQueries';
import { TrialChanges, TrialOverview, TrialSize } from '../components/TrialSteps';
import { OptimizeTrialDialog, RemoveTrialDialog, TrialProgressCard, trialTone, useRemoveTrial } from '../components/TrialParts';
import { download } from '../lib/download';
import { fmtBytes, fmtClock, fmtRows, fmtTime } from '../lib/format';
import { routes } from '../lib/routes';
import { vars } from '../lib/style';
import { STEPS, buildGuide, hasPlain, stepOf, type Compare, type StepKey } from '../lib/trialGuide';
import { trialReport } from '../lib/trialReport';

/**
 * One trial. While it runs: its progress. Once it is done: one verdict that stays in view, and six steps that lead
 * from the overview through what changed, size, codecs and speed to applying the candidate.
 */
export function TrialPage() {
  const { id = '', tab } = useParams();
  const step = stepOf(tab);
  const navigate = useNavigate();
  const { data: trial, error, reload } = useApi(() => api.trial(id), [id]);
  const [removing, setRemoving] = useRemoveTrial();
  const [optimizing, setOptimizing] = useState(false);
  const [compare, setCompare] = useState<Compare>('today');
  const [query, setQuery] = useState<string | null>(null);
  const [picks, setPicks] = useState<Record<string, boolean>>({});
  usePolling(trial?.status === 'running' || trial?.apply?.status === 'running' || trial?.optimize?.status === 'running', 2000, () => void reload(true));

  const r = trial?.result ?? null;
  const done = trial?.status === 'completed' && r != null;
  const guide = useMemo(() => (trial && r && done ? buildGuide(trial, r, compare) : null), [trial, r, done, compare]);

  // The verdict stays at the top while the steps scroll under it; whatever sticks or scrolls below needs its height.
  const verdictBar = useRef<HTMLElement>(null);
  const stepNav = useRef<HTMLElement>(null);
  const barHeight = () => (verdictBar.current && getComputedStyle(verdictBar.current).position === 'sticky' ? verdictBar.current.offsetHeight : 0);
  useEffect(() => {
    const bar = verdictBar.current;
    if (!bar) return;
    const root = document.documentElement;
    const set = () => root.style.setProperty('--tg-verdict-h', `${barHeight()}px`);
    set();
    const observer = new ResizeObserver(set);
    observer.observe(bar);
    window.addEventListener('resize', set);
    return () => {
      observer.disconnect();
      window.removeEventListener('resize', set);
      root.style.removeProperty('--tg-verdict-h');
    };
  }, [guide != null]);
  // A new step starts at its top: after "Next" at the foot of a long step the list of steps is far above.
  const seen = useRef(step);
  useEffect(() => {
    if (seen.current === step) return;
    seen.current = step;
    const nav = stepNav.current;
    if (nav && nav.getBoundingClientRect().top < barHeight()) window.scrollTo({ top: nav.getBoundingClientRect().top + window.scrollY - barHeight() - 16 });
  }, [step]);

  if (error && !trial) {
    return (
      <>
        <PageHeader eyebrow={<Link to="/trials">Trials</Link>} title={`Trial #${id}`} />
        <ErrorState message={error} onRetry={() => reload()} />
      </>
    );
  }
  if (!trial) return <Loading />;

  const statusWord =
    trial.status === 'completed'
      ? `Completed ${fmtClock(trial.finishedAt)}`
      : trial.status === 'running'
        ? `Running · ${Math.round(trial.progress)}%`
        : trial.status === 'cancelled'
          ? `Cancelled ${fmtClock(trial.finishedAt)}`
          : `Failed ${fmtClock(trial.finishedAt)}`;
  const at = STEPS.findIndex((s) => s.key === step);
  const to = (key: StepKey) => routes.trial(trial.id, key === 'overview' ? undefined : key);
  const go = (key: StepKey, label?: string) => {
    if (label) setQuery(label);
    navigate(to(key));
  };
  const tables = r?.plainDdl ? 'all three tables' : 'both tables';

  return (
    <>
      <PageHeader
        eyebrow={
          <Link to="/trials">
            Trials · {trial.database}.{trial.table} · #{trial.id}
          </Link>
        }
        title={trial.name}
        description={
          <span className="body-sm g-num row">
            <Status tone={trialTone(trial)}>{statusWord}</Status>
            {done && r ? <> · {fmtRows(r.rows)} identical rows in {tables}</> : null}
            {trial.status !== 'running' ? <> · {fmtBytes(trial.scratchBytes)} in scratch, kept until you remove it</> : null}
          </span>
        }
        actions={
          trial.status === 'running' ? undefined : (
            <>
              {guide && r ? (
                <Button variant="secondary" onClick={() => download(`trial-${trial.id}.md`, trialReport(trial, r, guide), 'text/markdown')}>
                  Export report
                </Button>
              ) : null}
              <Button variant="secondary" onClick={() => setRemoving(trial)}>
                Remove trial
              </Button>
            </>
          )
        }
      />

      {trial.status === 'running' ? <TrialProgressCard trial={trial} onChanged={() => void reload(true)} /> : null}

      {trial.status === 'failed' || trial.status === 'cancelled' ? (
        <Card as="section" style={{ alignItems: 'flex-start' }}>
          <Status tone={trial.status === 'failed' ? 'error' : 'muted'} className="subhead">
            {trial.status === 'failed' ? 'The trial failed' : 'The trial was cancelled'}
          </Status>
          {trial.error ? <p className="body-sm muted" style={{ overflowWrap: 'anywhere' }}>{trial.error}</p> : null}
          <p className="caption muted">Removing the trial drops whatever it left in {trial.scratchDatabase}.</p>
        </Card>
      ) : null}

      {guide && r ? (
        <>
          <section aria-label="Verdict" className="tg-verdict" ref={verdictBar}>
            <div className="tg-verdict__text">
              <p className="eyebrow">Verdict</p>
              <p className="tg-verdict__words">{guide.verdict}</p>
            </div>
            <dl className="tg-kpis">
              <div>
                <dt className="eyebrow">Size</dt>
                <dd className="g-num">{guide.kpis.size}</dd>
              </div>
              <div>
                <dt className="eyebrow">Queries</dt>
                <dd className="g-num">{guide.kpis.queries}</dd>
              </div>
              <div>
                <dt className="eyebrow">Inserts</dt>
                <dd className="g-num">{guide.kpis.inserts}</dd>
              </div>
            </dl>
            {hasPlain(r) ? (
              <div role="group" aria-label="Compare the candidate with" className="tg-compare">
                <span className="eyebrow">Compare candidate with</span>
                <div className="row">
                  <Choice pressed={guide.cmp === 'today'} onClick={() => setCompare('today')}>
                    Today's table
                  </Choice>
                  <Choice pressed={guide.cmp === 'defaults'} onClick={() => setCompare('defaults')}>
                    Plain defaults
                  </Choice>
                </div>
              </div>
            ) : null}
          </section>

          <nav aria-label="Result steps" className="tg-steps" ref={stepNav}>
            {STEPS.map((s, i) => (
              <Link key={s.key} to={to(s.key)} className="tg-step" aria-current={s.key === step ? 'step' : undefined}>
                <span className="tg-step__name">
                  <span className="g-count g-num">{i + 1}</span>
                  {s.label}
                </span>
                <span className="g-num tg-step__sub">{guide.subs[s.key]}</span>
              </Link>
            ))}
          </nav>

          {step === 'overview' ? <TrialOverview guide={guide} onGo={go} /> : null}
          {step === 'changes' ? <TrialChanges trial={trial} result={r} guide={guide} /> : null}
          {step === 'size' ? <TrialSize trial={trial} result={r} guide={guide} /> : null}
          {step === 'codecs' ? <TrialCodecs trial={trial} result={r} picks={picks} onPicks={setPicks} /> : null}
          {step === 'speed' ? <TrialSpeed trial={trial} result={r} guide={guide} selected={query} onSelect={setQuery} /> : null}
          {step === 'apply' ? <TrialApply trial={trial} result={r} onChanged={() => void reload(true)} onOptimize={() => setOptimizing(true)} /> : null}

          <footer className="tg-foot">
            <Button variant="secondary" to={at > 0 ? to(STEPS[at - 1].key) : undefined} disabled={at === 0}>
              Back
            </Button>
            <Button variant="dark" to={at < STEPS.length - 1 ? to(STEPS[at + 1].key) : undefined} disabled={at === STEPS.length - 1}>
              {at < STEPS.length - 1 ? `Next: ${STEPS[at + 1].label}` : 'Last step'}
            </Button>
          </footer>
        </>
      ) : null}

      {trial.log.length && !done ? (
        <Card as="section" style={{ gap: 12 }}>
          <h2 className="subhead">Run log</h2>
          <ol className="body-sm g-num muted stack" style={{ margin: 0, paddingLeft: 20, ...vars({ gap: 6 }) }}>
            {trial.log.map((l, i) => (
              <li key={i} style={{ overflowWrap: 'anywhere' }}>
                <span className="ink">{fmtTime(l.at)}</span> {l.message}
              </li>
            ))}
          </ol>
        </Card>
      ) : null}

      {optimizing ? (
        <OptimizeTrialDialog
          trial={trial}
          onClose={() => setOptimizing(false)}
          onStarted={() => {
            setOptimizing(false);
            void reload(true);
          }}
        />
      ) : null}
      {removing ? (
        <RemoveTrialDialog
          trial={removing}
          onClose={() => setRemoving(null)}
          onRemoved={() => {
            setRemoving(null);
            navigate('/trials');
          }}
        />
      ) : null}
    </>
  );
}
