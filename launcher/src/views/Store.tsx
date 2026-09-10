import { PxBox, TT } from '../components/px/Px';

export default function Store() {
  return (
    <div className="page">
      <div className="page__head">
        <h1 className="page__title">Store</h1>
      </div>
      <PxBox family="panel" className="empty">
        <TT size={22} tone="dim">
          NOTHING FOR SALE
        </TT>
        <span className="meta">
          The store is wired into the shell but has no catalogue behind it yet — when
          cosmetics ship, they land here. Nothing in DuskLauncher is pay-to-win, and the
          launcher never sells anything the game itself gates.
        </span>
      </PxBox>
    </div>
  );
}
