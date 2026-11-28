import { useState, type FormEvent, type ReactNode } from 'react';
import { Button } from '../components/Button';
import { TextInput } from '../components/Form';
import { Logo } from '../components/Layout';
import { PasswordFields, usePasswordForm } from '../components/PasswordForm';
import { InlineError } from '../components/States';
import { useAction } from '../hooks/useApi';
import { useAuth } from '../state/auth';

function AuthCard({ title, children, onSubmit }: { title: string; children: ReactNode; onSubmit: () => void }) {
  return (
    <div className="auth">
      <form
        className="card auth-card"
        onSubmit={(e: FormEvent) => {
          e.preventDefault();
          onSubmit();
        }}
        noValidate
      >
        <div className="brand" style={{ padding: 0 }}>
          <Logo />
          Granulee
        </div>
        <h1 className="headline">{title}</h1>
        {children}
      </form>
    </div>
  );
}

export function SignInPage() {
  const { signIn } = useAuth();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const action = useAction();

  return (
    <AuthCard title="Sign in" onSubmit={() => void action.run(() => signIn(username, password))}>
      <TextInput label="User name" autoComplete="username" autoCapitalize="none" spellCheck={false} autoFocus value={username} onChange={(e) => setUsername(e.target.value)} required />
      <TextInput label="Password" type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} required />
      <InlineError message={action.error} />
      <Button type="submit" stretch busy={action.busy} disabled={!username.trim() || !password}>
        Sign in
      </Button>
    </AuthCard>
  );
}

/** The first sign-in with a password somebody else chose or saw: nothing else is reachable until it is replaced. */
export function OwnPasswordPage() {
  const { me, changePassword, signOut } = useAuth();
  const form = usePasswordForm();
  const action = useAction();

  return (
    <AuthCard
      title="Choose your password"
      onSubmit={() => {
        if (!form.problem) void action.run(() => changePassword(form.current, form.password));
      }}
    >
      <p className="body-sm muted">
        {me?.username}, the password you signed in with was a one-time one. Choose your own to go on.
      </p>
      <PasswordFields form={form} currentLabel="One-time password" />
      <InlineError message={action.error ?? (form.touched ? form.problem : null)} />
      <Button type="submit" stretch busy={action.busy} disabled={!form.complete}>
        Set password
      </Button>
      <button type="button" className="g-link" style={{ alignSelf: 'center' }} onClick={() => void signOut()}>
        Sign out
      </button>
    </AuthCard>
  );
}
