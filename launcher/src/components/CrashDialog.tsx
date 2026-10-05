/**
 * Shown when the game exits with an error the player didn't cause by
 * pressing STOP: what went wrong and what to do, in plain words, worked out
 * by the backend from the crash report and the end of the log.
 */
import { useState } from 'react';
import { createPortal } from 'react-dom';
import { PxBox, PxButton, TT } from './px/Px';
import { api, type CrashInfo } from '../lib/api';

export default function CrashDialog({
  crash,
  instance,
  onClose,
}: {
  crash: CrashInfo;
  instance: string | null;
  onClose: () => void;
}) {
  const [note, setNote] = useState<string | null>(null);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(`${crash.title}\n\n${crash.details}`);
      setNote('Copied. Paste it wherever you ask for help.');
    } catch {
      setNote("Couldn't reach the clipboard.");
    }
  };

  const reveal = async () => {
    if (!crash.report) return;
    try {
      await api.revealCrashReport(crash.profileId, crash.report);
    } catch (e) {
      setNote(String(e));
    }
  };

  return createPortal(
    <div className="modal-scrim" onClick={onClose}>
      <PxBox
        family="red"
        className="px--window modal crash"
        role="alertdialog"
        aria-label="The game crashed"
        onClick={(e) => e.stopPropagation()}
      >
        <TT size={22} tone="red">
          THE GAME CRASHED
        </TT>
        <span className="crash__title">{crash.title}</span>
        <ul className="crash__advice meta">
          {crash.advice.map((a) => (
            <li key={a}>{a}</li>
          ))}
        </ul>
        <span className="meta crash__foot">
          {note ??
            `${instance ? `${instance} · ` : ''}exit code ${crash.code}${crash.report ? ` · ${crash.report}` : ''}`}
        </span>
        <div className="modal__row modal__row--tall">
          {crash.report && (
            <PxButton family="grey" height="md" onClick={() => void reveal()}>
              <TT size={20}>SHOW REPORT</TT>
            </PxButton>
          )}
          <PxButton family="grey" height="md" onClick={() => void copy()}>
            <TT size={20}>COPY DETAILS</TT>
          </PxButton>
          <PxButton family="blue" height="md" autoFocus onClick={onClose}>
            <TT size={20} tone="blue">
              OK
            </TT>
          </PxButton>
        </div>
      </PxBox>
    </div>,
    document.body,
  );
}
