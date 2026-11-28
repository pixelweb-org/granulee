import { useState } from 'react';
import { TextInput } from './Form';

export const MIN_PASSWORD = 12;

/** What keeps a new password from being sent, in words; null when nothing does. The server checks again. */
export function passwordProblem(password: string, repeat?: string): string | null {
  if (password.length < MIN_PASSWORD) return `The password must have at least ${MIN_PASSWORD} characters`;
  if (repeat !== undefined && repeat !== password) return 'The two new passwords differ';
  return null;
}

export interface PasswordForm {
  current: string;
  password: string;
  repeat: string;
  setCurrent: (v: string) => void;
  setPassword: (v: string) => void;
  setRepeat: (v: string) => void;
  /** Every field has something in it. */
  complete: boolean;
  /** The repeat field was typed in, so a mismatch is worth mentioning. */
  touched: boolean;
  problem: string | null;
}

/** The current password and the new one, twice: for the dialog in the sidebar and for the first sign-in. */
export function usePasswordForm(initialCurrent = ''): PasswordForm {
  const [current, setCurrent] = useState(initialCurrent);
  const [password, setPassword] = useState('');
  const [repeat, setRepeat] = useState('');
  return {
    current,
    password,
    repeat,
    setCurrent,
    setPassword,
    setRepeat,
    complete: current.length > 0 && password.length > 0 && repeat.length > 0,
    touched: repeat.length > 0,
    problem: passwordProblem(password, repeat),
  };
}

export function PasswordFields({ form, currentLabel = 'Current password' }: { form: PasswordForm; currentLabel?: string }) {
  return (
    <>
      <TextInput label={currentLabel} type="password" autoComplete="current-password" value={form.current} onChange={(e) => form.setCurrent(e.target.value)} required />
      <TextInput
        label="New password"
        type="password"
        autoComplete="new-password"
        value={form.password}
        onChange={(e) => form.setPassword(e.target.value)}
        help={`At least ${MIN_PASSWORD} characters. A sentence is fine.`}
        required
      />
      <TextInput label="New password, again" type="password" autoComplete="new-password" value={form.repeat} onChange={(e) => form.setRepeat(e.target.value)} required />
    </>
  );
}
