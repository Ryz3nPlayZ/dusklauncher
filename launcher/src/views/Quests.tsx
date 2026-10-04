import { useCallback, useEffect, useState } from 'react';
import { PxBox, PxButton, TT } from '../components/px/Px';
import { api, isTauri, type Account, type Quest, type Quests as Board } from '../lib/api';

/**
 * Quests: the daily streak, the daily and weekly boards, and achievements.
 * The server tracks progress (play time comes from the mod); rewards wait
 * here, and in game, until CLAIM. `onClaimable` keeps the nav badge honest.
 */
export default function Quests({
  account,
  onClaimable,
}: {
  account: Account | null;
  onClaimable: (n: number) => void;
}) {
  const [board, setBoard] = useState<Board | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  // the browser preview has no account but mocks the board
  const signedIn = !isTauri || !!account?.authenticated;

  const take = useCallback(
    (b: Board) => {
      setBoard(b);
      onClaimable(b.claimable);
    },
    [onClaimable],
  );

  useEffect(() => {
    if (!signedIn) return;
    let live = true;
    const load = () =>
      api
        .getQuests()
        .then((b) => live && take(b))
        .catch((e) => live && setNote(String(e)));
    void load();
    // the reset timers count down and the mod moves progress while you play
    const t = window.setInterval(load, 60_000);
    return () => {
      live = false;
      window.clearInterval(t);
    };
  }, [signedIn, take]);

  const claim = async (id: string) => {
    setBusy(id);
    setNote(null);
    try {
      const r = await api.claimQuest(id);
      take(r.quests);
      setNote(r.paid > 0 ? `+${r.paid} coins` : 'Nothing to claim yet.');
    } catch (e) {
      setNote(String(e));
    } finally {
      setBusy(null);
    }
  };

  if (!signedIn) {
    return (
      <div className="page">
        <div className="page__head">
          <h1 className="page__title">Quests</h1>
        </div>
        <PxBox family="panel" className="stack">
          <span className="meta">Sign in with Microsoft to earn coins from quests.</span>
        </PxBox>
      </div>
    );
  }

  const s = board?.streak;
  return (
    <div className="page">
      <div className="page__head">
        <h1 className="page__title">Quests</h1>
        <div className="quests__head">
          {note && <span className="meta">{note}</span>}
          {board && (
            <TT size={20} tone="yellow">
              {`${board.coins} COINS`}
            </TT>
          )}
          <PxButton
            family={board && board.claimable > 0 ? 'accent' : 'grey'}
            height="md"
            disabled={!board || board.claimable === 0 || busy !== null}
            onClick={() => void claim('all')}
          >
            <TT size={16} tone={board && board.claimable > 0 ? 'accent' : 'sub'}>
              {busy === 'all' ? 'CLAIMING…' : `CLAIM ALL${board && board.claimable > 0 ? ` (${board.claimable})` : ''}`}
            </TT>
          </PxButton>
        </div>
      </div>

      {!board ? (
        <PxBox family="panel" className="stack">
          <span className="meta">Loading…</span>
        </PxBox>
      ) : (
        <div className="column">
          {s && (
            <PxBox family={s.done && !s.claimed ? 'accent' : 'panel'} className="stack">
              <div className="quests__row">
                <div className="srow__text">
                  <TT size={20} tone={s.days > 0 ? 'accent' : undefined}>
                    {s.days > 0 ? `${s.days} DAY STREAK` : 'START A STREAK'}
                  </TT>
                  <span className="meta">
                    {s.done
                      ? 'Today counts. Come back tomorrow to keep it going.'
                      : `Play ${s.needMinutes} minutes today to keep it: ${Math.min(s.todayMinutes, s.needMinutes)} / ${s.needMinutes} min`}
                  </span>
                </div>
                <ClaimButton
                  done={s.done}
                  claimed={s.claimed}
                  coins={s.coins}
                  busy={busy === 'streak'}
                  disabled={busy !== null}
                  onClick={() => void claim('streak')}
                />
              </div>
              <Bar progress={s.todayMinutes} goal={s.needMinutes} />
              <div className="quests__cycle">
                {s.cycle.map((c, i) => (
                  <PxBox
                    key={i}
                    family={i < s.cycleDay || (i === s.cycleDay && s.done) ? 'accent' : i === s.cycleDay ? 'soft' : 'panel'}
                    className="quests__day"
                  >
                    <TT size={11} tone="dim">
                      {`DAY ${i + 1}`}
                    </TT>
                    <TT size={14} tone={i <= s.cycleDay ? 'yellow' : 'sub'}>
                      {`+${c}`}
                    </TT>
                  </PxBox>
                ))}
              </div>
            </PxBox>
          )}

          <Section
            label="DAILY"
            reset={board.dailyReset}
            quests={board.daily}
            busy={busy}
            onClaim={(id) => void claim(id)}
          />
          <Section
            label="WEEKLY"
            reset={board.weeklyReset}
            quests={board.weekly}
            busy={busy}
            onClaim={(id) => void claim(id)}
          />
          <Section
            label="ACHIEVEMENTS"
            hint="Claimed achievements show as badges on your profile."
            quests={board.achievements}
            busy={busy}
            onClaim={(id) => void claim(id)}
          />
        </div>
      )}
    </div>
  );
}

function Section({
  label,
  reset,
  hint,
  quests,
  busy,
  onClaim,
}: {
  label: string;
  reset?: number;
  hint?: string;
  quests: Quest[];
  busy: string | null;
  onClaim: (id: string) => void;
}) {
  return (
    <PxBox family="panel" className="stack quests__section">
      <div className="quests__row">
        <TT size={16} tone="dim">
          {label}
        </TT>
        {reset !== undefined && <span className="meta">new in {until(reset)}</span>}
        {hint && <span className="meta">{hint}</span>}
      </div>
      {quests.map((q) => (
        <div key={q.id} className="srow quests__quest">
          <div className="srow__text">
            <TT size={16} tone={q.claimed ? 'sub' : undefined}>
              {q.title}
            </TT>
            <Bar progress={q.progress} goal={q.goal} />
            <span className="meta">
              {amount(Math.min(q.progress, q.goal), q.unit)} / {amount(q.goal, q.unit)}
            </span>
          </div>
          <ClaimButton
            done={q.done}
            claimed={q.claimed}
            coins={q.coins}
            busy={busy === q.id}
            disabled={busy !== null}
            onClick={() => onClaim(q.id)}
          />
        </div>
      ))}
    </PxBox>
  );
}

function ClaimButton({
  done,
  claimed,
  coins,
  busy,
  disabled,
  onClick,
}: {
  done: boolean;
  claimed: boolean;
  coins: number;
  busy: boolean;
  disabled: boolean;
  onClick: () => void;
}) {
  const ready = done && !claimed;
  return (
    <div className="srow__control">
      <PxButton family={ready ? 'accent' : 'grey'} height="md" disabled={!ready || disabled} onClick={onClick}>
        <TT size={16} tone={ready ? 'accent' : claimed ? 'sub' : 'yellow'}>
          {claimed ? 'CLAIMED' : busy ? 'CLAIMING…' : ready ? `CLAIM +${coins}` : `+${coins}`}
        </TT>
      </PxButton>
    </div>
  );
}

function Bar({ progress, goal }: { progress: number; goal: number }) {
  const f = goal > 0 ? Math.min(1, progress / goal) : 0;
  return (
    <div className="quests__bar">
      <div className="quests__fill" style={{ width: `${f * 100}%` }} />
    </div>
  );
}

/** minutes read as hours once they pass an hour */
function amount(n: number, unit: string) {
  if (unit !== 'min') return String(n);
  return n >= 60 ? `${Math.floor(n / 60)}h${n % 60 ? ` ${n % 60}m` : ''}` : `${n}m`;
}

function until(secs: number) {
  const m = Math.max(0, Math.floor(secs / 60));
  const d = Math.floor(m / 1440);
  const h = Math.floor((m % 1440) / 60);
  return d > 0 ? `${d}d ${h}h` : `${h}h ${m % 60}m`;
}
