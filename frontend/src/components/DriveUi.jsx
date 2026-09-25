import { Link } from 'react-router-dom';
import { useEffect, useRef, useState } from 'react';
import { Icon } from '../icons';

/** Anchored dropdown that closes on outside click or Escape. */
export function Popover({ trigger, children, align = 'left', className = '' }) {
  const [open, setOpen] = useState(false);
  const ref = useRef(null);

  useEffect(() => {
    if (!open) return undefined;
    const onDown = (e) => ref.current && !ref.current.contains(e.target) && setOpen(false);
    const onKey = (e) => e.key === 'Escape' && setOpen(false);
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  return (
    <div className={`popover ${className}`} ref={ref}>
      {trigger({ open, toggle: () => setOpen((o) => !o) })}
      {open && (
        <div className={`popover-menu ${align}`} onClick={() => setOpen(false)}>
          {children}
        </div>
      )}
    </div>
  );
}

export function MenuItem({ icon, children, onClick, danger, disabled }) {
  return (
    <button className={`menu-item ${danger ? 'danger' : ''}`} onClick={onClick} disabled={disabled}>
      {icon && <Icon name={icon} size={20} />}
      <span>{children}</span>
    </button>
  );
}

/** Drive-style filter chip: outlined pill with a caret; turns blue with a clear (x) when a value is chosen. */
export function FilterPill({ label, options, value, onChange }) {
  const selected = options.find(([k]) => k === value);
  return (
    <Popover
      trigger={({ toggle, open }) => (
        <button className={`pill-btn ${selected ? 'active' : ''} ${open ? 'open' : ''}`} onClick={toggle}>
          {selected && <Icon name="check" size={18} />}
          <span>{selected ? selected[1] : label}</span>
          {selected ? (
            <span
              className="pill-clear"
              role="button"
              aria-label={`Clear ${label} filter`}
              onClick={(e) => { e.stopPropagation(); onChange(''); }}
            >
              <Icon name="close" size={16} />
            </span>
          ) : (
            <Icon name="caret" size={20} />
          )}
        </button>
      )}
    >
      {options.map(([key, text]) => (
        <button key={key} className={`menu-item ${key === value ? 'selected' : ''}`} onClick={() => onChange(key)}>
          <span className="menu-check">{key === value && <Icon name="check" size={18} />}</span>
          <span>{text}</span>
        </button>
      ))}
    </Popover>
  );
}

/** Full-width gradient band at the top of every page: breadcrumb, title, subtitle, actions, optional extra content. */
export function PageHero({ crumbs = [], title, subtitle, actions, children }) {
  return (
    <section className="page-hero">
      <div className="container">
        {crumbs.length > 0 && (
          <nav className="breadcrumb" aria-label="Breadcrumb">
            {crumbs.map(([label, to], i) => (
              <span key={label}>
                {to ? <Link to={to}>{label}</Link> : <span>{label}</span>}
                {i < crumbs.length - 1 && <Icon name="caret" size={16} className="crumb-caret" />}
              </span>
            ))}
          </nav>
        )}
        <h1 className="hero-title">{title}</h1>
        {subtitle && <p className="hero-sub">{subtitle}</p>}
        {actions && <div className="hero-actions">{actions}</div>}
        {children}
      </div>
    </section>
  );
}
