import { useCallback, useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { convertFileSrc } from '@tauri-apps/api/core';
import PixelGlyph from '../components/px/PixelGlyph';
import { NavCell, PxBox, PxButton, TT } from '../components/px/Px';
import { api, isTauri, type Friend, type Recording, type Screenshot } from '../lib/api';
import { fileSize, stamp } from '../lib/time';

/* ── MEDIA ────────────────────────────────────────────────────────────────
   Every instance's screenshots, clips and replays, newest first: the
   instances layout again, one tile each. A screenshot's VIEW opens it full
   size (← / → step through), the star keeps it under FAVORITES, SEND drops
   it into a friend's chat. A clip or replay's WATCH launches the instance
   that recorded it straight into the replay viewer — no menus on the way.
   The red square always moves the file to the OS trash. */

const FILTERS = ['SCREENSHOTS', 'FAVORITES', 'CLIPS', 'REPLAYS'] as const;
type Filter = (typeof FILTERS)[number];

const errText = (e: unknown) => String(e).replace(/^Error: /, '');
/* the browser preview's fixture paths are already data URLs */
const srcOf = (s: Screenshot) => (isTauri ? convertFileSrc(s.path) : s.path);

/** m:ss, or h:mm:ss past the hour */
function length(ms: number): string {
  const t = Math.round(ms / 1000);
  const h = Math.floor(t / 3600);
  const m = Math.floor((t % 3600) / 60);
  const sec = String(t % 60).padStart(2, '0');
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${sec}` : `${m}:${sec}`;
}

export default function Screenshots({
  clock24h,
  gameBusy,
  onWatch,
  onBack,
}: {
  clock24h: boolean;
  /** a game is already up (one at a time) */
  gameBusy: boolean;
  onWatch: (profileId: string, path: string) => void;
  onBack: () => void;
}) {
  const [filter, setFilter] = useState<Filter>('SCREENSHOTS');
  const [shots, setShots] = useState<Screenshot[] | null>(null);
  const [recordings, setRecordings] = useState<Recording[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const [viewing, setViewing] = useState<string | null>(null);
  const [sending, setSending] = useState<Screenshot | null>(null);

  const refresh = useCallback(() => {
    void api
      .listScreenshots()
      .then(setShots)
      .catch((e) => {
        setShots([]);
        setError(errText(e));
      });
  }, []);
  useEffect(refresh, [refresh]);

  const recordingTab = filter === 'CLIPS' || filter === 'REPLAYS';
  useEffect(() => {
    if (!recordingTab || recordings) return;
    void api
      .listRecordings()
      .then(setRecordings)
      .catch((e) => {
        setRecordings([]);
        setError(errText(e));
      });
  }, [recordingTab, recordings]);

  const shown = (shots ?? []).filter((s) => filter === 'SCREENSHOTS' || s.favorite);
  const kind = filter === 'CLIPS' ? 'clip' : 'replay';
  const takes = (recordings ?? []).filter((r) => r.kind === kind);
  const viewIndex = viewing ? shown.findIndex((s) => s.path === viewing) : -1;
  const current = viewIndex >= 0 ? shown[viewIndex] : null;

  const favorite = async (s: Screenshot) => {
    setError(null);
    try {
      await api.setScreenshotFavorite(s.path, !s.favorite);
      setShots((list) => (list ?? []).map((x) => (x.path === s.path ? { ...x, favorite: !s.favorite } : x)));
    } catch (e) {
      setError(errText(e));
    }
  };

  const remove = async (s: Screenshot) => {
    setError(null);
    try {
      await api.deleteScreenshot(s.path);
      setShots((list) => (list ?? []).filter((x) => x.path !== s.path));
      setNote(`${s.name} moved to the trash.`);
      if (viewing === s.path) setViewing(null);
    } catch (e) {
      setError(errText(e));
    }
  };

  const reveal = (s: Screenshot) => {
    setError(null);
    void api.revealScreenshot(s.path).catch((e) => setError(errText(e)));
  };

  const removeRecording = async (r: Recording) => {
    setError(null);
    try {
      await api.deleteRecording(r.path);
      setRecordings((list) => (list ?? []).filter((x) => x.path !== r.path));
      setNote(`${r.name} moved to the trash.`);
    } catch (e) {
      setError(errText(e));
    }
  };

  const revealRecording = (r: Recording) => {
    setError(null);
    void api.revealRecording(r.path).catch((e) => setError(errText(e)));
  };

  // arrow keys step through the open shot, escape closes it
  useEffect(() => {
    if (!current || sending) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setViewing(null);
      else if (e.key === 'ArrowLeft' && viewIndex > 0) setViewing(shown[viewIndex - 1].path);
      else if (e.key === 'ArrowRight' && viewIndex < shown.length - 1) setViewing(shown[viewIndex + 1].path);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [current, sending, viewIndex, shown]);

  return (
    <div className="page">
      <div className="page__head">
        {/* the title leads; BACK sits on the right, beside any CTA (as in the
           instance editor and project pages) */}
        <h1 className="page__title">Media</h1>
        <PxButton family="red" height="md" className="browse__back" onClick={onBack}>
          <PixelGlyph glyph="left" size={22} color="var(--r-co)" />
          <TT size={20} tone="red">
            BACK
          </TT>
        </PxButton>
      </div>

      <div className="win">
        <div className="win__bar">
          {FILTERS.map((f) => (
            <NavCell
              key={f}
              label={f}
              active={filter === f}
              onClick={() => {
                setFilter(f);
                setNote(null);
                // a fresh look each time: the game may have saved more since
                if (f === 'CLIPS' || f === 'REPLAYS') setRecordings(null);
              }}
            />
          ))}
          <div className="win__fill">
            {(error || note) && <span className="meta editor__count">{error ?? note}</span>}
          </div>
        </div>

        <div className="win__body win__body--tiles">
          {recordingTab ? (
            recordings && takes.length === 0 ? (
              <PxBox family="panel" className="empty">
                <TT size={20} tone="dim">
                  {filter === 'CLIPS' ? 'NO CLIPS YET' : 'NO REPLAYS YET'}
                </TT>
                <span className="meta">
                  {filter === 'CLIPS'
                    ? 'In game, set RECORDING to CLIPS under MEDIA → SETTINGS, then press F8 to save the last 30 seconds (or however long you set).'
                    : 'In game, set RECORDING to FULL REPLAY under MEDIA → SETTINGS. Every session is saved here when you leave.'}
                </span>
              </PxBox>
            ) : (
              <div className="grid scroll">
                {takes.map((r) => (
                  <PxBox key={r.path} family="grey" className="card wall shot">
                    <RecordingThumb path={r.path} />
                    <span className="card__name">{r.profileName.toUpperCase()}</span>
                    <span className="card__info">
                      {stamp(r.recordedAt, clock24h)} · {length(r.durationMs)} · {fileSize(r.size)}
                    </span>
                    <span className="card__info">
                      {r.server || 'Singleplayer'}
                      {r.mcVersion && ` · ${r.mcVersion}`}
                    </span>
                    <div className="card__row">
                      <PxButton
                        family="install"
                        height="fill"
                        className="card__play"
                        disabled={!isTauri || gameBusy}
                        title={
                          gameBusy
                            ? 'Close the game first'
                            : `Opens ${r.profileName} straight into this ${r.kind}`
                        }
                        onClick={() => onWatch(r.profileId, r.path)}
                      >
                        <TT size={16}>WATCH</TT>
                      </PxButton>
                      <PxButton
                        family="grey"
                        height="fill"
                        className="card__gear"
                        disabled={!isTauri}
                        title="Show in folder"
                        onClick={() => revealRecording(r)}
                      >
                        <PixelGlyph glyph="box" size={16} color="var(--text-2)" />
                      </PxButton>
                      <PxButton
                        family="red"
                        height="fill"
                        className="card__gear"
                        title="Move to trash"
                        onClick={() => void removeRecording(r)}
                      >
                        <PixelGlyph glyph="close" size={16} color="var(--r-co)" />
                      </PxButton>
                    </div>
                  </PxBox>
                ))}
              </div>
            )
          ) : shots && shown.length === 0 ? (
            <PxBox family="panel" className="empty">
              <TT size={20} tone="dim">
                {filter === 'SCREENSHOTS' ? 'NO SCREENSHOTS YET' : 'NO FAVORITES YET'}
              </TT>
              <span className="meta">
                {filter === 'SCREENSHOTS'
                  ? 'Press F2 in game. Every instance’s screenshots show up here.'
                  : 'Star a screenshot to keep it here.'}
              </span>
            </PxBox>
          ) : (
            <div className="grid scroll">
              {shown.map((s) => (
                <PxBox key={s.path} family="grey" className="card wall shot">
                  <button className="card__banner-frame shot__open" title="View" onClick={() => setViewing(s.path)}>
                    <img className="card__banner" src={srcOf(s)} alt="" loading="lazy" draggable={false} />
                  </button>
                  <span className="card__name">{s.profileName.toUpperCase()}</span>
                  <span className="card__info">
                    {stamp(s.takenAt, clock24h)} · {fileSize(s.size)}
                  </span>
                  <div className="card__row">
                    <PxButton family="grey" height="fill" className="card__play" onClick={() => setViewing(s.path)}>
                      <TT size={22} tone="accent" sx={1.15}>
                        VIEW
                      </TT>
                    </PxButton>
                    <PxButton
                      family={s.favorite ? 'accent' : 'grey'}
                      height="fill"
                      className="card__gear"
                      title={s.favorite ? 'Unfavorite' : 'Favorite'}
                      onClick={() => void favorite(s)}
                    >
                      <PixelGlyph glyph="star" size={18} color={s.favorite ? 'var(--accent-co)' : 'var(--text-2)'} />
                    </PxButton>
                    <PxButton family="red" height="fill" className="card__gear" title="Move to trash" onClick={() => void remove(s)}>
                      <PixelGlyph glyph="close" size={16} color="var(--r-co)" />
                    </PxButton>
                  </div>
                </PxBox>
              ))}
            </div>
          )}
        </div>
      </div>

      {current &&
        createPortal(
          <div className="modal-scrim" onClick={() => setViewing(null)}>
            <PxBox family="panel" className="px--window modal shot__viewer" onClick={(e) => e.stopPropagation()}>
              <div className="shot__stage">
                <img src={srcOf(current)} alt={current.name} draggable={false} />
              </div>
              <div className="shot__caption">
                <TT size={16}>{current.profileName.toUpperCase()}</TT>
                <span className="meta">
                  {current.name} · {stamp(current.takenAt, clock24h)} · {fileSize(current.size)}
                </span>
              </div>
              <div className="modal__row modal__row--tall">
                <PxButton
                  family="grey"
                  height="md"
                  className="shot__step"
                  disabled={viewIndex <= 0}
                  title="Newer"
                  onClick={() => setViewing(shown[viewIndex - 1].path)}
                >
                  <PixelGlyph glyph="left" size={18} color="var(--text-2)" />
                </PxButton>
                <PxButton family="grey" height="md" disabled={!isTauri} onClick={() => reveal(current)}>
                  <TT size={16}>SHOW IN FOLDER</TT>
                </PxButton>
                <PxButton family="blue" height="md" onClick={() => setSending(current)}>
                  <TT size={16} tone="blue">
                    SEND TO FRIEND
                  </TT>
                </PxButton>
                <PxButton
                  family={current.favorite ? 'accent' : 'grey'}
                  height="md"
                  className="shot__step"
                  title={current.favorite ? 'Unfavorite' : 'Favorite'}
                  onClick={() => void favorite(current)}
                >
                  <PixelGlyph glyph="star" size={18} color={current.favorite ? 'var(--accent-co)' : 'var(--text-2)'} />
                </PxButton>
                <PxButton
                  family="grey"
                  height="md"
                  className="shot__step"
                  disabled={viewIndex >= shown.length - 1}
                  title="Older"
                  onClick={() => setViewing(shown[viewIndex + 1].path)}
                >
                  <PixelGlyph glyph="right" size={18} color="var(--text-2)" />
                </PxButton>
              </div>
            </PxBox>
          </div>,
          document.body,
        )}

      {sending &&
        createPortal(
          <SendPicker
            shot={sending}
            onClose={() => setSending(null)}
            onSent={(to) => {
              setSending(null);
              setNote(`Sent to ${to}.`);
            }}
          />,
          document.body,
        )}
    </div>
  );
}

/** a recording's saved thumbnail, read out of the file once the tile is on screen */
function RecordingThumb({ path }: { path: string }) {
  const [src, setSrc] = useState<string | null>(null);
  const [el, setEl] = useState<HTMLDivElement | null>(null);

  useEffect(() => {
    if (!el) return;
    let live = true;
    const seen = new IntersectionObserver((entries) => {
      if (!entries.some((e) => e.isIntersecting)) return;
      seen.disconnect();
      void api
        .recordingThumb(path)
        .then((url) => live && setSrc(url))
        .catch(() => {});
    });
    seen.observe(el);
    return () => {
      live = false;
      seen.disconnect();
    };
  }, [el, path]);

  return (
    <div ref={setEl} className="card__banner-frame">
      {src ? (
        <img className="card__banner" src={src} alt="" draggable={false} />
      ) : (
        <span className="card__banner wall__stub" />
      )}
    </div>
  );
}

/** the friends list as a column of buttons; one click uploads and sends */
function SendPicker({ shot, onClose, onSent }: { shot: Screenshot; onClose: () => void; onSent: (to: string) => void }) {
  const [friends, setFriends] = useState<Friend[] | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void api
      .listFriends()
      .then(setFriends)
      .catch((e) => {
        setFriends([]);
        setError(errText(e));
      });
  }, []);

  const send = async (f: Friend) => {
    setBusy(f.uuid);
    setError(null);
    try {
      await api.sendScreenshot(f.uuid, shot.path);
      onSent(f.username);
    } catch (e) {
      setError(errText(e));
      setBusy(null);
    }
  };

  return (
    <div className="modal-scrim shot__picker-scrim" onClick={() => !busy && onClose()}>
      <PxBox family="panel" className="px--window modal" role="dialog" aria-label="Send screenshot" onClick={(e) => e.stopPropagation()}>
        <TT size={22}>SEND TO FRIEND</TT>
        <span className="meta">It shows up in their chat with you. Up to 8 MB.</span>
        <div className="shot__friends scroll">
          {friends?.length === 0 && !error && <span className="meta">No friends yet — add some from the social pane.</span>}
          {friends?.map((f) => (
            <PxButton
              key={f.uuid}
              family="grey"
              height="md"
              disabled={busy !== null}
              onClick={() => void send(f)}
            >
              <TT size={16}>{busy === f.uuid ? 'SENDING…' : f.username.toUpperCase()}</TT>
            </PxButton>
          ))}
        </div>
        {error && (
          <PxBox family="red" height="md" className="social__notice" role="alert">
            <span className="meta">{error}</span>
          </PxBox>
        )}
        <div className="modal__row modal__row--tall">
          <PxButton family="grey" height="md" disabled={busy !== null} onClick={onClose}>
            <TT size={20}>CANCEL</TT>
          </PxButton>
        </div>
      </PxBox>
    </div>
  );
}
