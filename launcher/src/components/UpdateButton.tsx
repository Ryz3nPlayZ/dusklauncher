/**
 * The corner update button. Absent until there is something to do; after
 * that it is the single control for the whole flow: install → restart.
 */
import { PxButton, TT } from './px/Px';
import type { UpdateStatus } from '../lib/updater';

export default function UpdateButton({
  status,
  onInstall,
  onRestart,
}: {
  status: UpdateStatus;
  onInstall: () => void;
  onRestart: () => void;
}) {
  switch (status.kind) {
    case 'available':
      return (
        <PxButton family="install" height="sm" className="update-btn" title={status.notes ?? undefined} onClick={onInstall}>
          <TT size={13}>{`UPDATE TO v${status.version}`}</TT>
        </PxButton>
      );
    case 'downloading':
      return (
        <PxButton family="install" height="sm" className="update-btn" disabled>
          <TT size={13} tone="dim">
            {status.pct == null ? 'DOWNLOADING…' : `DOWNLOADING ${status.pct}%`}
          </TT>
        </PxButton>
      );
    case 'ready':
      return (
        <PxButton family="accent" height="sm" className="update-btn" onClick={onRestart}>
          <TT size={13}>RESTART TO UPDATE</TT>
        </PxButton>
      );
    case 'error':
      return (
        <PxButton family="red" height="sm" className="update-btn" title={status.message} onClick={onInstall}>
          <TT size={13}>UPDATE FAILED — RETRY</TT>
        </PxButton>
      );
    default:
      return null;
  }
}
