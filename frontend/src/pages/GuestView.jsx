import { PageHero } from '../components/DriveUi';
import SignIn from '../components/SignIn';
import { Icon } from '../icons';

const POINTS = [
  ['server', 'You choose the copies', 'Keep 1 to 4 copies of every file, each on a different storage server.'],
  ['check', 'Verified end to end', 'Every copy is checked against its SHA-256 fingerprint on upload, on download and in the background.'],
  ['restore', 'Repairs itself', 'If a server fails or a copy is damaged, Vault notices and restores the missing copy automatically.'],
  ['shield', 'Private to you', 'Sign in with Google. Only you can see, download or change your files.'],
];

/** What signed-out visitors see on every page: the product pitch and a Google sign-in call to action. */
export default function GuestView({ title }) {
  return (
    <div className="files-view">
      <PageHero
        crumbs={title ? [['Vault', '/'], [title]] : []}
        title="Cloud storage that repairs itself"
        subtitle="Reliable, redundant and verified storage for your files."
        actions={<SignIn large />}
      />
      <div className="container page-body">
        <div className="feature-grid">
          {POINTS.map(([icon, heading, text]) => (
            <div key={heading} className="feature-card">
              <span className="feature-icon"><Icon name={icon} size={22} /></span>
              <h3>{heading}</h3>
              <p className="muted">{text}</p>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
