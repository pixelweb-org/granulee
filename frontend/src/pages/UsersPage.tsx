import { useState } from 'react';
import { api } from '../api/client';
import type { Role, User } from '../api/types';
import { Button } from '../components/Button';
import { Card } from '../components/Card';
import { Dialog } from '../components/Dialog';
import { Select, TextInput } from '../components/Form';
import { PageHeader } from '../components/PageHeader';
import { MIN_PASSWORD, passwordProblem } from '../components/PasswordForm';
import { ErrorState, InlineError, Loading } from '../components/States';
import { Status } from '../components/StatusDot';
import { useAction, useApi } from '../hooks/useApi';
import { fmtAgo } from '../lib/format';
import { vars } from '../lib/style';
import { useAuth } from '../state/auth';

const ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789';

/** Made in the browser, so a one-time password never comes out of the server. */
function generatePassword(): string {
  const random = crypto.getRandomValues(new Uint32Array(20));
  return Array.from(random, (n) => ALPHABET[n % ALPHABET.length]).join('');
}

/** A password the admin passes on, so it is shown in the clear. */
function OneTimePassword({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  return (
    <div className="stack" style={vars({ gap: 8 })}>
      <TextInput
        label="One-time password"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        autoComplete="off"
        spellCheck={false}
        help={`At least ${MIN_PASSWORD} characters. Pass it on yourself: Granulee shows it nowhere again, and the user has to choose their own at the first sign-in.`}
        required
      />
      <div>
        <Button variant="secondary" small onClick={() => onChange(generatePassword())}>
          Generate
        </Button>
      </div>
    </div>
  );
}

function AddUserDialog({ onClose, onDone }: { onClose: () => void; onDone: () => void }) {
  const [username, setUsername] = useState('');
  const [role, setRole] = useState<Role>('user');
  const [password, setPassword] = useState('');
  const action = useAction();
  const problem = passwordProblem(password);

  async function submit() {
    if (problem || !username.trim()) return;
    if (await action.run(() => api.createUser(username.trim(), password, role))) onDone();
  }

  return (
    <Dialog
      title="Add user"
      onClose={onClose}
      actions={
        <>
          <Button variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" form="user-form" busy={action.busy} disabled={!username.trim() || !password}>
            Add user
          </Button>
        </>
      }
    >
      <form
        id="user-form"
        className="stack"
        onSubmit={(e) => {
          e.preventDefault();
          void submit();
        }}
      >
        <TextInput label="User name" value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="off" autoCapitalize="none" spellCheck={false} required />
        <Select label="Role" value={role} onChange={(e) => setRole(e.target.value as Role)} help="An admin also manages the users. Nobody sees another user's data sources, an admin included.">
          <option value="user">User</option>
          <option value="admin">Admin</option>
        </Select>
        <OneTimePassword value={password} onChange={setPassword} />
        <InlineError message={action.error ?? (password ? problem : null)} />
      </form>
    </Dialog>
  );
}

function ResetPasswordDialog({ user, onClose, onDone }: { user: User; onClose: () => void; onDone: () => void }) {
  const [password, setPassword] = useState('');
  const action = useAction();
  const problem = passwordProblem(password);

  async function submit() {
    if (problem) return;
    if (await action.run(() => api.resetPassword(user.id, password))) onDone();
  }

  return (
    <Dialog
      title={`Reset the password of ${user.username}`}
      onClose={onClose}
      actions={
        <>
          <Button variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" form="reset-form" busy={action.busy} disabled={!password}>
            Reset password
          </Button>
        </>
      }
    >
      <form
        id="reset-form"
        className="stack"
        onSubmit={(e) => {
          e.preventDefault();
          void submit();
        }}
      >
        <p className="body-sm muted">{user.username} is signed out everywhere and signs in with this password next.</p>
        <OneTimePassword value={password} onChange={setPassword} />
        <InlineError message={action.error ?? (password ? problem : null)} />
      </form>
    </Dialog>
  );
}

export function UsersPage() {
  const { me } = useAuth();
  const { data: users, error, loading, reload } = useApi(() => api.users(), []);
  const [adding, setAdding] = useState(false);
  const [resetting, setResetting] = useState<User | null>(null);
  const [deactivating, setDeactivating] = useState<User | null>(null);
  const action = useAction();

  async function setActive(user: User, active: boolean) {
    await action.run(() => (active ? api.activateUser(user.id) : api.deactivateUser(user.id)));
    setDeactivating(null);
    await reload(true);
  }

  return (
    <>
      <PageHeader
        eyebrow="Settings"
        title="Users"
        description="Who may sign in. Every user has their own data sources, scans and trials, and nobody sees another user's."
        actions={<Button onClick={() => setAdding(true)}>Add user</Button>}
      />

      {error && !users ? (
        <ErrorState message={error} onRetry={() => void reload()} />
      ) : loading || !users ? (
        <Loading />
      ) : (
        <>
          <Card table>
            <table className="g-tbl">
              <thead>
                <tr>
                  <th>User name</th>
                  <th>Role</th>
                  <th>Status</th>
                  <th>Last sign-in</th>
                  <th className="r">
                    <span className="sr-only">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {users.map((u) => (
                  <tr key={u.id}>
                    <td className="g-strong nowrap">
                      {u.username}
                      {u.id === me?.id ? <span className="muted" style={{ fontWeight: 400 }}> (you)</span> : null}
                    </td>
                    <td>{u.role === 'admin' ? 'Admin' : 'User'}</td>
                    <td className="nowrap">
                      {!u.active ? (
                        <Status tone="muted">Deactivated</Status>
                      ) : u.mustChangePassword ? (
                        <Status tone="warning">One-time password</Status>
                      ) : (
                        <Status tone="success">Active</Status>
                      )}
                    </td>
                    <td className="nowrap">{fmtAgo(u.lastLoginAt) ?? 'Never'}</td>
                    <td className="r nowrap">
                      {u.id === me?.id ? null : (
                        <span className="row" style={{ justifyContent: 'flex-end', flexWrap: 'nowrap', ...vars({ gap: 16 }) }}>
                          <button type="button" className="g-link" onClick={() => setResetting(u)}>
                            Reset password
                          </button>
                          {u.active ? (
                            <button type="button" className="g-link" onClick={() => setDeactivating(u)}>
                              Deactivate
                            </button>
                          ) : (
                            <button type="button" className="g-link" disabled={action.busy} onClick={() => void setActive(u, true)}>
                              Activate
                            </button>
                          )}
                        </span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Card>
          <InlineError message={deactivating ? null : action.error} />
        </>
      )}

      {adding ? (
        <AddUserDialog
          onClose={() => setAdding(false)}
          onDone={() => {
            setAdding(false);
            void reload(true);
          }}
        />
      ) : null}

      {resetting ? (
        <ResetPasswordDialog
          user={resetting}
          onClose={() => setResetting(null)}
          onDone={() => {
            setResetting(null);
            void reload(true);
          }}
        />
      ) : null}

      {deactivating ? (
        <Dialog
          title={`Deactivate ${deactivating.username}?`}
          onClose={() => setDeactivating(null)}
          actions={
            <>
              <Button variant="secondary" onClick={() => setDeactivating(null)}>
                Cancel
              </Button>
              <Button danger busy={action.busy} onClick={() => void setActive(deactivating, false)}>
                Deactivate
              </Button>
            </>
          }
        >
          <p className="body">
            {deactivating.username} is signed out at once and cannot sign in. Their data sources, scans and trials are kept, and scheduled scans pause until the
            account is activated again. A trial that is running finishes.
          </p>
          <InlineError message={action.error} />
        </Dialog>
      ) : null}
    </>
  );
}
