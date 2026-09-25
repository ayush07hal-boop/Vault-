import { PageHero } from '../components/DriveUi';
import { Icon } from '../icons';

/**
 * Vault has no desktop sync client yet, so there are no "computers" to list. This page says so plainly
 * rather than showing invented data.
 */
export default function ComputersView() {
  return (
    <div className="files-view">
      <PageHero crumbs={[['Vault', '/'], ['Computers']]} title="Computers" subtitle="Folders synced from your devices." />
      <div className="container page-body">
        <div className="files-empty">
          <Icon name="computer" size={64} />
          <h2>No computers connected</h2>
          <p className="muted">
            Syncing folders from a computer to Vault isn't available yet. Your files live on Vault's storage
            servers and are managed from the web.
          </p>
        </div>
      </div>
    </div>
  );
}
