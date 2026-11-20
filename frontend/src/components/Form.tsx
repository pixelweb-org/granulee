import { useId, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes } from 'react';

export function Field({ label, htmlFor, help, helpId, children }: { label: string; htmlFor: string; help?: ReactNode; helpId?: string; children: ReactNode }) {
  return (
    <div className="cc-field">
      <label htmlFor={htmlFor} className="cc-field__label">
        {label}
      </label>
      {children}
      {help ? (
        <span id={helpId} className="cc-field__help">
          {help}
        </span>
      ) : null}
    </div>
  );
}

interface TextInputProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'className'> {
  label: string;
  help?: ReactNode;
  numeric?: boolean;
}

export function TextInput({ label, help, numeric, id, ...rest }: TextInputProps) {
  const auto = useId();
  const inputId = id ?? auto;
  const helpId = help ? `${inputId}-help` : undefined;
  return (
    <Field label={label} htmlFor={inputId} help={help} helpId={helpId}>
      <input id={inputId} className={`cc-field__input${numeric ? ' g-num' : ''}`} aria-describedby={helpId} {...rest} />
    </Field>
  );
}

interface SelectProps extends Omit<SelectHTMLAttributes<HTMLSelectElement>, 'className'> {
  label: string;
  help?: ReactNode;
  children: ReactNode;
}

export function Select({ label, help, id, children, ...rest }: SelectProps) {
  const auto = useId();
  const selectId = id ?? auto;
  const helpId = help ? `${selectId}-help` : undefined;
  return (
    <Field label={label} htmlFor={selectId} help={help} helpId={helpId}>
      <select id={selectId} className="g-select" aria-describedby={helpId} {...rest}>
        {children}
      </select>
    </Field>
  );
}

export function Checkbox({ checked, onChange, disabled, children }: { checked: boolean; onChange: (checked: boolean) => void; disabled?: boolean; children: ReactNode }) {
  return (
    <label className="check">
      <input type="checkbox" checked={checked} disabled={disabled} onChange={(e) => onChange(e.target.checked)} />
      <span>{children}</span>
    </label>
  );
}
