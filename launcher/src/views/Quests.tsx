import { useCallback, useEffect, useState } from 'react';
import { Check, Fire } from '@phosphor-icons/react';
import { api, isTauri, type Account, type Quest, type Quests as Board, type Streak } from '../lib/api';
import { LEGACY } from '../lib/ui';
import QuestsLegacy from './legacy/QuestsLegacy';

const errText = (e: unknown) => String(e).replace(/^Error: /, '');
const coins = (n: number) => n.toLocaleString();

type QuestsProps = {
  account: Account | null;
  onClaimable: (n: number) => void;
};

/**
 * Quests: the daily streak, the daily and weekly boards side by side, and
 * achievements. The server tracks progress (play time comes from the mod);
 * rewards wait here, and in game, until Claim. `onClaimable` keeps the nav
 * badge honest. A reward reads as a button only once it can be claimed.
 */
export default function Quests(props: QuestsProps) {
  if (LEGACY) return <QuestsLegacy {...props} />;
  const { account, onClaimable } = props;
  const [board, setBoard] = useState<Board | null>(null);
  const [note, setNote] = useState<string | null>(null);
  /* why the board couldn't load; the next good refresh clears it */
  const [loadErr, setLoadErr] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  // the browser preview has no account but mocks the board
  const signedIn = !isTauri || !!account?.authenticated || !!account?.offline;

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
        .then((b) => {
          if (!live) return;
          take(b);
          setLoadErr(null);
        })
        .catch((e) => live && setLoadErr(errText(e)));
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
      setNote(r.paid > 0 ? `+${coins(r.paid)} coins` : 'Nothing to claim yet.');
    } catch (e) {
      setNote(errText(e));
    } finally {
      setBusy(null);
    }
  };

  const ready = board?.claimable ?? 0;
  return (
    <div className="page quests">
      <div className="page__head">
        <h1 className="page__title">Quests</h1>
        {(note ?? (board && loadErr)) && <span className="quests__note">{note ?? loadErr}</span>}
        {board && (
          <span className="quests__balance" title="Your coins">
            <CoinDot />
            <span className="num">{coins(board.coins)}</span>
          </span>
        )}
        {ready > 0 && (
          <button className="quests__claim quests__claim--all" disabled={busy !== null} onClick={() => void claim('all')}>
            {busy === 'all' ? 'Claiming…' : `Claim all (${ready})`}
          </button>
        )}
      </div>

      {!signedIn ? (
        <div className="quests__empty">Sign in to earn coins from quests.</div>
      ) : !board ? (
        <div className="quests__empty">{loadErr ?? 'Loading…'}</div>
      ) : (
        <div className="quests__body scroll">
          <StreakCard s={board.streak} busy={busy} onClaim={() => void claim('streak')} />

          <div className="quests__boards">
            <Board title="Daily" reset={board.dailyReset} quests={board.daily} busy={busy} onClaim={(id) => void claim(id)} />
            <Board title="Weekly" reset={board.weeklyReset} quests={board.weekly} busy={busy} onClaim={(id) => void claim(id)} />
          </div>

          <section className="quests__panel">
            <header className="quests__panel-head">
              <h2>Achievements</h2>
              <span>Claimed ones show as badges on your profile</span>
            </header>
            <div className="quests__badges">
              {board.achievements.map((q) => (
                <div key={q.id} className={['quests__badge', q.claimed ? 'is-claimed' : ''].join(' ')}>
                  <span className="quests__title">{q.title}</span>
                  <Progress q={q} />
                  <Reward q={q} busy={busy} onClaim={() => void claim(q.id)} />
                </div>
              ))}
            </div>
          </section>
        </div>
      )}
    </div>
  );
}

/** the streak: how long it is, whether today counts yet, and the week's payouts */
function StreakCard({ s, busy, onClaim }: { s: Streak; busy: string | null; onClaim: () => void }) {
  const today = Math.min(s.todayMinutes, s.needMinutes);
  return (
    <section className={['quests__streak', s.days > 0 ? 'is-going' : ''].join(' ')}>
      <div className="quests__streak-top">
        <span className="quests__flame">
          <Fire size={26} weight="fill" />
        </span>
        <div className="quests__streak-text">
          <span className="quests__streak-days">
            {s.days > 0 ? (
              <>
                <span className="num">{s.days}</span> day streak
              </>
            ) : (
              'Start a streak'
            )}
          </span>
          <span className="quests__streak-sub">
            {s.done
              ? 'Today counts. Play tomorrow to keep it going.'
              : `Play ${s.needMinutes} minutes today to keep it: ${today} of ${s.needMinutes} so far`}
          </span>
          {!s.done && (
            <div className="quests__bar quests__bar--streak">
              <div className="quests__fill" style={{ width: `${(today / Math.max(1, s.needMinutes)) * 100}%` }} />
            </div>
          )}
        </div>
        <Reward
          q={{ done: s.done, claimed: s.claimed, coins: s.coins }}
          busy={busy}
          id="streak"
          onClaim={onClaim}
        />
      </div>

      <ol className="quests__week" aria-label="This week's streak rewards">
        {s.cycle.map((c, i) => {
          const done = i < s.cycleDay || (i === s.cycleDay && s.done);
          const today = i === s.cycleDay;
          return (
            <li
              key={i}
              className={['quests__day', done ? 'is-done' : '', today ? 'is-today' : '', i === s.cycle.length - 1 ? 'is-bonus' : '']
                .filter(Boolean)
                .join(' ')}
            >
              <span className="quests__day-label">{today ? 'Today' : `Day ${i + 1}`}</span>
              <span className="quests__day-mark">{done ? <Check size={14} weight="bold" /> : <CoinDot />}</span>
              <span className="quests__day-coins num">+{c}</span>
            </li>
          );
        })}
      </ol>
    </section>
  );
}

function Board({
  title,
  reset,
  quests,
  busy,
  onClaim,
}: {
  title: string;
  reset: number;
  quests: Quest[];
  busy: string | null;
  onClaim: (id: string) => void;
}) {
  const left = quests.filter((q) => !q.claimed).length;
  return (
    <section className="quests__panel">
      <header className="quests__panel-head">
        <h2>{title}</h2>
        <span>
          {left === 0 ? 'All done · ' : ''}new in {until(reset)}
        </span>
      </header>
      <div className="quests__list">
        {quests.map((q) => (
          <div key={q.id} className={['quests__quest', q.claimed ? 'is-claimed' : ''].join(' ')}>
            <div className="quests__quest-text">
              <span className="quests__title">{q.title}</span>
              <Progress q={q} />
            </div>
            <Reward q={q} busy={busy} id={q.id} onClaim={() => onClaim(q.id)} />
          </div>
        ))}
      </div>
    </section>
  );
}

/** a bar with "12m / 20m" beside it */
function Progress({ q }: { q: Quest }) {
  const f = q.goal > 0 ? Math.min(1, q.progress / q.goal) : 0;
  return (
    <div className="quests__progress">
      <div className="quests__bar">
        <div className="quests__fill" style={{ width: `${f * 100}%` }} />
      </div>
      <span className="quests__count num">
        {amount(Math.min(q.progress, q.goal), q.unit)} / {amount(q.goal, q.unit)}
      </span>
    </div>
  );
}

/** what a quest pays: plain coins while it's in progress, a Claim button once
    it's done, a check once it's paid */
function Reward({
  q,
  busy,
  id,
  onClaim,
}: {
  q: Pick<Quest, 'done' | 'claimed' | 'coins'> & { id?: string };
  busy: string | null;
  id?: string;
  onClaim: () => void;
}) {
  const key = id ?? q.id;
  if (q.claimed)
    return (
      <span className="quests__reward is-claimed">
        <Check size={14} weight="bold" />
        Claimed
      </span>
    );
  if (q.done)
    return (
      <button className="quests__claim" disabled={busy !== null} onClick={onClaim}>
        {busy === key ? 'Claiming…' : `Claim +${coins(q.coins)}`}
      </button>
    );
  return (
    <span className="quests__reward" title="Paid when you finish it">
      <CoinDot />
      <span className="num">+{coins(q.coins)}</span>
    </span>
  );
}

/** a gold coin, sized off the text beside it */
function CoinDot() {
  return <span className="coin" aria-hidden="true" />;
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
